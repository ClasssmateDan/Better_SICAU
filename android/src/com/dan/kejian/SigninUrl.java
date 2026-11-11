package com.dan.kejian;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class SigninUrl {
    private static final long SOURCE_TIME_OFFSET_MS = 28800000L;

    private static String payload(String value) {
        if (!isAllowed(value)) throw new IllegalArgumentException("签到链接格式不正确");
        try {
            String query = new URI(value).getRawQuery();
            return new String(Base64.getDecoder().decode(URLDecoder.decode(query.substring(3), "UTF-8")), StandardCharsets.UTF_8);
        } catch (Exception error) { throw new IllegalArgumentException("签到链接格式不正确"); }
    }

    public static long generatedAt(String value) {
        String decoded = payload(value);
        return Long.parseLong(decoded.substring(decoded.indexOf(',') + 1)) - SOURCE_TIME_OFFSET_MS;
    }

    /** A manual refresh preserves the course and existing source time rule. */
    public static String renew(String value, long nowMs) {
        String decoded = payload(value);
        try {
            long timestamp = Math.addExact(nowMs, SOURCE_TIME_OFFSET_MS);
            String fresh = decoded.substring(0, decoded.indexOf(',')) + "," + timestamp;
            String result = "https://eol.sicau.edu.cn/Roll/qrsign?qr=" + URLEncoder.encode(Base64.getEncoder().encodeToString(fresh.getBytes(StandardCharsets.UTF_8)), "UTF-8");
            if (nowMs < 0 || !isAllowed(result)) throw new IllegalArgumentException();
            return result;
        } catch (Exception error) { throw new IllegalArgumentException("当前时间无法生成有效签到链接"); }
    }

    public static boolean isAllowed(String value) {
        try {
            if (value == null || value.length() > 4096) return false;
            URI uri = new URI(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !"eol.sicau.edu.cn".equalsIgnoreCase(uri.getHost()) || !"/Roll/qrsign".equals(uri.getPath())) return false;
            if (uri.getUserInfo() != null || uri.getFragment() != null || uri.getPort() != -1 && uri.getPort() != 443) return false;
            String query = uri.getRawQuery();
            if (query == null || !query.startsWith("qr=") || query.contains("&")) return false;
            String encoded = URLDecoder.decode(query.substring(3), "UTF-8");
            String decoded = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            return decoded.matches("[0-9]{1,32},[0-9]{13}");
        } catch (Exception error) { return false; }
    }
}
