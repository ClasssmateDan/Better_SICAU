package com.dan.qrbatch.core;

public final class SignResult {
    public enum State {
        SUCCESS, ALREADY_SIGNED, FAILED, EXPIRED, AUTH_REQUIRED, UNCERTAIN,
        CANCELLED, WAITING_NEW_CODE, SKIPPED
    }

    public final State state;
    public final String message;

    public SignResult(State state, String message) {
        if (state == null) throw new IllegalArgumentException("结果状态不能为空。");
        this.state = state;
        this.message = message == null ? "" : message;
    }
}
