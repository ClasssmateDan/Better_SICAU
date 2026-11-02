package com.dan.qrbatch.core;

import java.util.LinkedHashMap;
import java.util.Map;

/** An account owns its cookie jar; no cookie map is shared between accounts. */
public final class Account {
    public final String phone;
    public final String uid;
    public final String name;
    public final String fid;
    public final Map<String, String> cookies;
    public boolean selected = true;

    public Account(String phone, String uid, String name, String fid, Map<String, String> cookies) {
        this.phone = value(phone);
        this.uid = value(uid).trim();
        this.name = value(name);
        this.fid = value(fid);
        this.cookies = new LinkedHashMap<String, String>();
        if (cookies != null) this.cookies.putAll(cookies);
    }

    private static String value(String value) { return value == null ? "" : value; }
}
