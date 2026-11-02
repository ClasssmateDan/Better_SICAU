package com.dan.qrbatch.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A synchronous queue. Cancellation never changes an in-flight result or retries a request. */
public final class BatchRunner {
    public static final class Entry {
        public final Account account;
        public final SignResult result;
        public Entry(Account account, SignResult result) { this.account = account; this.result = result; }
    }

    public interface Listener { void onProgress(int finished, int total, Entry entry); }

    private final SigninGateway gateway;
    private volatile boolean cancelled;

    public BatchRunner(SigninGateway gateway) {
        if (gateway == null) throw new IllegalArgumentException("签到客户端不能为空。");
        this.gateway = gateway;
    }

    public void cancel() { cancelled = true; }

    public synchronized List<Entry> run(List<PreparedAccount> prepared, QrPayload qr,
            LocationValue location, Set<String> completed, Listener listener) {
        if (prepared == null || qr == null || completed == null) {
            throw new IllegalArgumentException("账号队列、二维码和已完成记录不能为空。");
        }
        List<PreparedAccount> queue = new ArrayList<PreparedAccount>();
        Set<String> uids = new HashSet<String>();
        for (PreparedAccount item : prepared) {
            if (item == null) throw new IllegalArgumentException("账号队列中不能包含空记录。");
            if (!item.account.selected) continue;
            String uid = item.account.uid;
            if (uid.isEmpty() || uid.indexOf('|') >= 0) {
                throw new IllegalArgumentException("账号缺少有效的用户编号，请重新登录。");
            }
            if (!uids.add(uid)) throw new IllegalArgumentException("队列包含重复用户编号，已取消执行。");
            queue.add(item);
        }
        LocationValue actualLocation = location == null ? LocationValue.empty() : location;
        List<Entry> entries = new ArrayList<Entry>();
        boolean expired = false;
        for (PreparedAccount item : queue) {
            String key = item.account.uid + "|" + qr.activeId;
            SignResult result;
            if (completed.contains(key)) {
                result = new SignResult(SignResult.State.SKIPPED, "该账号已成功处理此活动，已跳过。");
            } else if (cancelled) {
                result = new SignResult(SignResult.State.CANCELLED, "队列已取消，未提交该账号。");
            } else if (expired) {
                result = new SignResult(SignResult.State.WAITING_NEW_CODE, "二维码已过期，请扫描新码后继续。");
            } else {
                ActivityContext activity = item.findActivity(qr.activeId);
                if (activity == null) {
                    result = new SignResult(SignResult.State.SKIPPED, "该账号未找到二维码对应的活动，未提交。");
                } else {
                    try {
                        result = gateway.submit(item.account, activity, qr, actualLocation);
                        if (result == null) {
                            result = new SignResult(SignResult.State.UNCERTAIN, "未收到明确结果，请核实后再操作。");
                        }
                    } catch (Exception error) {
                        // A request may already have reached the server: never retry it automatically.
                        result = new SignResult(SignResult.State.UNCERTAIN, "请求结果不确定，未自动重试，请核实后再操作。");
                    }
                }
            }
            if (result.state == SignResult.State.SUCCESS || result.state == SignResult.State.ALREADY_SIGNED) {
                completed.add(key);
            }
            if (result.state == SignResult.State.EXPIRED) expired = true;
            Entry entry = new Entry(item.account, result);
            entries.add(entry);
            if (listener != null) listener.onProgress(entries.size(), queue.size(), entry);
        }
        return Collections.unmodifiableList(entries);
    }
}
