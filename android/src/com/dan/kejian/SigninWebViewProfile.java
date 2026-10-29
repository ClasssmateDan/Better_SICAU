package com.dan.kejian;

/** The WebView provider outlives Activities; configure its profile once per process. */
public final class SigninWebViewProfile {
    private static boolean initialized;
    private SigninWebViewProfile() {}

    public static synchronized void initialize(Runnable configure) {
        if (initialized) return;
        configure.run();
        initialized = true;
    }
}
