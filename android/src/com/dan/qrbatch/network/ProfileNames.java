package com.dan.qrbatch.network;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/** Parses only recognized name fields in the already-fetched page. Never loads URLs or executes scripts. */
final class ProfileNames {
    private ProfileNames() {}

    static String fromHtml(String html) {
        Document document = Jsoup.parse(html == null ? "" : html);
        // These elements may be inputs, spans or other text containers. Attribute order and quoting vary.
        for (String field : new String[]{"messageName", "realName", "realname"}) {
            for (Element element : document.select("[id=" + field + "], [name=" + field + "]")) {
                if (element.normalName().equals("script") || element.normalName().equals("style")) continue;
                String name = clean(element.attr("value"));
                if (name.isEmpty()) name = clean(element.text());
                if (name.isEmpty()) name = clean(element.attr("title"));
                if (!name.isEmpty()) return name;
            }
        }
        return "";
    }

    static String clean(String raw) {
        if (raw == null) return "";
        for (int i = 0; i < raw.length(); i++) if (Character.isISOControl(raw.charAt(i))) return "";
        String value = raw.replace('\u00a0', ' ').trim();
        if (value.isEmpty() || value.length() > 128 || value.equalsIgnoreCase("null") || value.equalsIgnoreCase("undefined")) return "";
        for (String placeholder : new String[]{"姓名", "真实姓名", "请输入姓名", "请输入真实姓名", "未填写", "未设置", "加载中", "修改姓名"}) {
            if (value.equals(placeholder)) return "";
        }
        // Never use markup, a URL, a template expression or a masked display name as the submission name.
        if (value.contains("<") || value.contains(">") || value.contains("${") || value.contains("{{")
                || value.contains("://") || value.contains("*") || value.indexOf('\u2026') >= 0 || value.contains("...")) return "";
        return value;
    }
}
