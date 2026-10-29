package com.dan.kejian;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class SigninUrl {
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
