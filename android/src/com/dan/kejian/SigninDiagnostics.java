package com.dan.kejian;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;
import android.provider.Settings;
import java.net.InetAddress;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import javax.net.ssl.HttpsURLConnection;

/** User-initiated anonymous homepage probe. Never reads WebView cookies or a sign-in URL. */
final class SigninDiagnostics {
    private static final String HOST = "eol.sicau.edu.cn";

    static String collect(Context context) {
        StringBuilder report = new StringBuilder("课间 · 签到网络诊断\n");
        report.append("检测时间：").append(time(System.currentTimeMillis())).append('\n');
        Network active = null;
        try {
            ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            active = manager.getActiveNetwork();
            NetworkCapabilities caps = active == null ? null : manager.getNetworkCapabilities(active);
            String network = caps == null ? "未知" : caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "Wi-Fi" : caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "移动网络" : caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? "以太网" : "其他";
            report.append("系统网络：").append(network).append('\n');
            report.append("系统识别 VPN：").append(caps == null ? "未知" : caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ? "是" : "否").append('\n');
        } catch (Exception error) { report.append("系统网络：无法读取\n"); }
        try {
            int automatic = Settings.Global.getInt(context.getContentResolver(), Settings.Global.AUTO_TIME, -1);
            report.append("自动设置时间：").append(automatic == 1 ? "开启" : automatic == 0 ? "关闭" : "未知").append('\n');
        } catch (Exception error) { report.append("自动设置时间：未知\n"); }
        report.append("目标域名：").append(HOST).append('\n');
        long dnsStarted = SystemClock.elapsedRealtime();
        try {
            InetAddress[] answers = active == null ? InetAddress.getAllByName(HOST) : active.getAllByName(HOST);
            Set<String> addresses = new LinkedHashSet<>();
            for (InetAddress answer : answers) addresses.add(answer.getHostAddress());
            report.append("系统 DNS：").append(SystemClock.elapsedRealtime() - dnsStarted).append(" ms\n");
            for (String address : addresses) report.append("  ").append(address).append('\n');
        } catch (Exception error) { report.append("系统 DNS：查询失败（").append(error.getClass().getSimpleName()).append("）\n"); }
        if (Thread.currentThread().isInterrupted()) return report.append("检测已停止").toString();
        HttpsURLConnection connection = null;
        long started = SystemClock.elapsedRealtime(), wallTime = System.currentTimeMillis();
        try {
            // Ordinary system TLS/hostname validation stays enabled. No redirects or account headers.
            URL homepage = new URL("https://" + HOST + "/");
            connection = (HttpsURLConnection) (active == null ? homepage.openConnection() : active.openConnection(homepage));
            connection.setRequestMethod("HEAD"); connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(4000); connection.setReadTimeout(4000); connection.setUseCaches(false);
            connection.setRequestProperty("User-Agent", "MicroMessenger");
            connection.setRequestProperty("Cache-Control", "no-cache"); connection.setRequestProperty("Cookie", "");
            int status = connection.getResponseCode();
            long elapsed = SystemClock.elapsedRealtime() - started;
            report.append("首页 HTTPS（系统线路）：HTTP ").append(status).append(" / ").append(elapsed).append(" ms\n");
            long serverTime = connection.getDate();
            if (serverTime > 0) report.append("首页 Date 与手机时差：约 ").append(String.format(Locale.ROOT, "%.1f", (serverTime - wallTime - elapsed / 2.0) / 1000.0)).append(" 秒\n");
            else report.append("首页 Date：未提供\n");
        } catch (Exception error) {
            report.append("首页 HTTPS：失败（").append(error.getClass().getSimpleName()).append("） / ").append(SystemClock.elapsedRealtime() - started).append(" ms\n");
        } finally { if (connection != null) connection.disconnect(); }
        return report.append("首页探测使用系统线路，IPv4 兼容下的浏览器连接另列。\n检测仅访问站点首页，不代表签到校验结果。").toString();
    }

    private static String time(long value) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        return format.format(new Date(value)) + "（北京时间）";
    }
}
