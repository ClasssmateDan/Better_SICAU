package com.dan.qrbatch.core;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Parses data locally; this class does not resolve hosts or open URLs. */
public final class QrPayload {
    public final String raw;
    public final String activeId;
    public final String enc;
    public final String code;

    private QrPayload(String raw, String activeId, String enc, String code) {
        this.raw = raw;
        this.activeId = activeId;
        this.enc = enc;
        this.code = code;
    }

    public static QrPayload parse(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new IllegalArgumentException("二维码内容不能为空。");
        }
        if (raw.length() > 100000) throw new IllegalArgumentException("二维码内容过长。");
        String input = raw.trim();
        String query;
        if (input.matches("(?s)^[A-Za-z][A-Za-z0-9+.-]*:.*")) {
            try {
                URI uri = new URI(input);
                String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.US);
                String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.US);
                boolean defaultPort = uri.getPort() == -1 ||
                        ("https".equals(scheme) && uri.getPort() == 443) ||
                        ("http".equals(scheme) && uri.getPort() == 80);
                if (!("http".equals(scheme) || "https".equals(scheme)) ||
                        !("chaoxing.com".equals(host) || host.endsWith(".chaoxing.com")) ||
                        uri.getRawUserInfo() != null || !defaultPort) {
                    throw new IllegalArgumentException("仅支持超星域名下的 HTTP/HTTPS 二维码链接。");
                }
                query = uri.getRawQuery();
            } catch (URISyntaxException error) {
                throw new IllegalArgumentException("二维码链接格式无效。", error);
            }
        } else {
            query = input.startsWith("?") ? input.substring(1) : input;
            int fragment = query.indexOf('#');
            if (fragment >= 0) query = query.substring(0, fragment);
        }
        if (query == null || query.isEmpty()) {
            throw new IllegalArgumentException("二维码缺少活动编号和 enc 参数。");
        }
        Map<String, List<String>> params = new LinkedHashMap<String, List<String>>();
        for (String part : query.split("&", -1)) {
            int equals = part.indexOf('=');
            String name = decode(equals < 0 ? part : part.substring(0, equals));
            String value = decode(equals < 0 ? "" : part.substring(equals + 1));
            List<String> values = params.get(name);
            if (values == null) {
                values = new ArrayList<String>();
                params.put(name, values);
            }
            values.add(value);
        }
        String id = one(params, "id", false);
        String activeId = one(params, "activeId", false);
        if (id != null && activeId != null && !id.equals(activeId)) {
            throw new IllegalArgumentException("id 与 activeId 参数冲突。");
        }
        String resolvedId = id == null ? activeId : id;
        if (resolvedId == null || !resolvedId.matches("[0-9]+")) {
            throw new IllegalArgumentException("二维码须包含唯一的数字活动编号 id 或 activeId。");
        }
        String enc = one(params, "enc", true);
        String code = one(params, "c", false);
        if (code != null && !code.matches("[0-9]+")) {
            throw new IllegalArgumentException("二维码 c 参数必须是非空的十进制数字。");
        }
        return new QrPayload(raw, resolvedId, enc, code == null ? "" : code);
    }

    private static String one(Map<String, List<String>> params, String key, boolean required) {
        List<String> values = params.get(key);
        if (values == null) {
            if (required) throw new IllegalArgumentException("二维码缺少 " + key + " 参数。");
            return null;
        }
        if (values.size() != 1) throw new IllegalArgumentException("二维码包含重复的 " + key + " 参数。");
        String value = values.get(0);
        if (value.trim().isEmpty()) throw new IllegalArgumentException("二维码 " + key + " 参数不能为空。");
        return value;
    }

    private static String decode(String input) {
        StringBuilder output = new StringBuilder();
        for (int i = 0; i < input.length();) {
            char ch = input.charAt(i);
            if (ch == '+') { output.append(' '); i++; }
            else if (ch != '%') { output.append(ch); i++; }
            else {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                while (i < input.length() && input.charAt(i) == '%') {
                    if (i + 2 >= input.length()) throw new IllegalArgumentException("二维码百分号编码不完整。");
                    int high = hex(input.charAt(i + 1));
                    int low = hex(input.charAt(i + 2));
                    if (high < 0 || low < 0) throw new IllegalArgumentException("二维码百分号编码无效。");
                    bytes.write((high << 4) | low);
                    i += 3;
                }
                try {
                    output.append(StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes.toByteArray())));
                } catch (CharacterCodingException error) {
                    throw new IllegalArgumentException("二维码包含无效的 UTF-8 编码。", error);
                }
            }
        }
        return output.toString();
    }

    private static int hex(char value) {
        if (value >= '0' && value <= '9') return value - '0';
        if (value >= 'A' && value <= 'F') return value - 'A' + 10;
        if (value >= 'a' && value <= 'f') return value - 'a' + 10;
        return -1;
    }
}
