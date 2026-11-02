package com.dan.qrbatch.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Offline assertions only: the fake gateway never opens a connection. */
public final class CoreTest {
    private static int assertions;

    public static void main(String[] args) {
        accountIsolation();
        preparedAccountSnapshot();
        locationValidation();
        qrParsing();
        qrRejections();
        sequentialAndSelected();
        activityMismatch();
        completedAndRescan();
        expiredStopsQueue();
        cancellationKeepsInflightResult();
        uncertainDoesNotRetry();
        duplicatesAreRejectedBeforeSubmission();
        cancelBeforeRunIsHonored();
        System.out.println("PASS: " + assertions + " offline core assertions");
    }

    private static void accountIsolation() {
        Map<String, String> original = new LinkedHashMap<String, String>();
        original.put("one", "1");
        original.put("two", "2");
        Account first = new Account("p1", "001", "甲", "f", original);
        Account second = new Account("p2", "002", "乙", "f", original);
        check(first.cookies instanceof LinkedHashMap, "cookie jar preserves insertion order");
        equal(Arrays.asList("one", "two"), new ArrayList<String>(first.cookies.keySet()), "cookie order");
        original.put("sourceOnly", "source");
        first.cookies.put("one", "changed");
        check(!first.cookies.containsKey("sourceOnly"), "copy does not follow source mutations");
        equal("1", second.cookies.get("one"), "account cookie isolation");
        check(first.selected, "accounts selected by default");
        equal("001", first.uid, "uid leading zero preserved");
        Account empty = new Account(null, "u", null, null, null);
        empty.cookies.put("updated", "yes");
        equal("yes", empty.cookies.get("updated"), "empty jar remains mutable");
    }

    private static void preparedAccountSnapshot() {
        ActivityContext activity = activity("42");
        List<ActivityContext> source = new ArrayList<ActivityContext>();
        source.add(activity);
        PreparedAccount prepared = new PreparedAccount(account("u"), source);
        source.clear();
        check(prepared.findActivity("42") == activity, "activity lookup and snapshot");
        check(prepared.findActivity("missing") == null, "missing activity returns null");
        check(prepared.findActivity(null) == null, "null activity lookup returns null");
        try {
            prepared.activities.clear();
            throw new AssertionError("activity snapshot must be immutable");
        } catch (UnsupportedOperationException expected) { assertions++; }
    }

    private static void locationValidation() {
        LocationValue empty = LocationValue.empty();
        check(!empty.isPresent(), "empty location absent");
        equal("", empty.longitude, "no invented longitude");
        equal("", empty.latitude, "no invented latitude");
        equal("", empty.altitude, "no invented altitude");
        check(!LocationValue.of("", " ", "ignored", "").isPresent(), "two blank coordinates absent");
        check(!LocationValue.of(null, null, null, null).isPresent(), "two null coordinates absent");
        LocationValue real = LocationValue.of(" 113.5 ", "34.8", " 自行提供的地址 ", "-20.5");
        check(real.isPresent(), "provided coordinates present");
        equal("113.5", real.longitude, "coordinate whitespace trimmed");
        equal("自行提供的地址", real.address, "provided address retained");
        equal("-20.5", real.altitude, "negative altitude accepted");
        check(LocationValue.of("-180", "90", "", "").isPresent(), "inclusive boundaries");
        check(LocationValue.of("180", "-90", "", "").isPresent(), "other inclusive boundaries");
        for (final String[] values : new String[][] {
                {"", "1", ""}, {"1", "", ""}, {"181", "0", ""},
                {"-180.01", "0", ""}, {"0", "90.01", ""}, {"0", "-91", ""},
                {"NaN", "0", ""}, {"Infinity", "0", ""}, {"0", "1e999", ""},
                {"0x1.0p0", "0", ""}, {"0", "0", "NaN"}, {"0", "0", "1e999"}}) {
            invalid(new Runnable() { public void run() { LocationValue.of(values[0], values[1], "", values[2]); } }, "bad location");
        }
    }

    private static void qrParsing() {
        String raw = "  https://mobilelearn.chaoxing.com/widget/sign/e?id=00042&c=001234&enc=LAST  ";
        QrPayload parsed = QrPayload.parse(raw);
        equal(raw, parsed.raw, "raw input preserved");
        equal("00042", parsed.activeId, "activity leading zero preserved");
        equal("001234", parsed.code, "c leading zero preserved");
        equal("LAST", parsed.enc, "enc at end parsed");
        equal("", QrPayload.parse("?id=42&enc=x").code, "c optional");
        equal("42", QrPayload.parse("activeId=42&enc=x").activeId, "activeId alias");
        equal("42", QrPayload.parse("id=42&activeId=42&enc=x").activeId, "identical id aliases accepted");
        equal("a+b c中文", QrPayload.parse("?id=42&enc=a%2Bb+c%E4%B8%AD%E6%96%87").enc, "form and UTF8 decoding");
        equal("x", QrPayload.parse("http://chaoxing.com/?id=42&enc=x").enc, "root host and http accepted");
        equal("x", QrPayload.parse("https://a.b.chaoxing.com:443/?id=42&enc=x").enc, "nested subdomain and default port");
        equal("x", QrPayload.parse("https://mobilelearn-ans.chaoxing.com/?id=42&enc=x").enc, "sibling subdomain");
        equal("<script>data</script>", QrPayload.parse("id=42&enc=%3Cscript%3Edata%3C%2Fscript%3E&__proto__=data").enc, "characters remain inert data");
        equal("x", QrPayload.parse("?id=42&%65nc=x").enc, "encoded parameter names decoded");
        equal("http://text.example", QrPayload.parse("id=42&enc=http://text.example").enc, "URL-shaped query values remain data");
    }

    private static void qrRejections() {
        for (final String raw : new String[] {
                "", "  ", "ordinary text", "?id=42", "?enc=x", "?id=&enc=x", "?id=42&enc=", "?id=42&enc=+",
                "?id=one&enc=x", "?id=42&id=42&enc=x", "?id=42&enc=x&enc=y", "?id=42&enc=x&%65nc=y",
                "?id=42&activeId=43&enc=x", "?id=042&activeId=42&enc=x", "?activeId=42&activeId=42&enc=x",
                "?id=42&enc=x&c=", "?id=42&enc=x&c=abc", "?id=42&enc=x&c=1&c=2", "?id=42&enc=%",
                "?id=42&enc=%ZZ", "?id=42&enc=%C3", "?id=42&enc=%FF", "?id=42&enc=%E0%A4", "?id=42&enc=%４１",
                "https://evil.example/?id=42&enc=x", "https://chaoxing.com.evil.example/?id=42&enc=x",
                "https://evilchaoxing.com/?id=42&enc=x", "https://chaoxing.com./?id=42&enc=x",
                "https://user@chaoxing.com/?id=42&enc=x", "https://chaoxing.com:8443/?id=42&enc=x",
                "javascript:alert(1)?id=42&enc=x", "file:///local?id=42&enc=x", "http://[?id=42&enc=x",
                "https://chaoxing.com/path#id=42&enc=x"}) {
            invalid(new Runnable() { public void run() { QrPayload.parse(raw); } }, "bad QR: " + raw);
        }
        invalid(new Runnable() { public void run() { QrPayload.parse(null); } }, "null QR");
    }

    private static void sequentialAndSelected() {
        FakeGateway gateway = new FakeGateway();
        List<PreparedAccount> queue = queue("u1", "u2", "u3");
        queue.get(1).account.selected = false;
        final List<Integer> finished = new ArrayList<Integer>();
        final List<Integer> totals = new ArrayList<Integer>();
        final List<BatchRunner.Entry> callbackEntries = new ArrayList<BatchRunner.Entry>();
        List<BatchRunner.Entry> entries = new BatchRunner(gateway).run(queue, qr(), LocationValue.empty(), new HashSet<String>(),
                new BatchRunner.Listener() { public void onProgress(int count, int total, BatchRunner.Entry entry) {
                    finished.add(count); totals.add(total); callbackEntries.add(entry);
                }});
        equal(Arrays.asList("u1", "u3"), gateway.calls, "selected accounts submit in source order");
        equal(Arrays.asList(1, 2), finished, "every selected account reports progress");
        equal(Arrays.asList(2, 2), totals, "total counts selected accounts");
        equal(2, entries.size(), "selected result count");
        check(entries.get(0) == callbackEntries.get(0), "callback receives exact returned entry");
        check(gateway.owner == Thread.currentThread(), "submission stays synchronous");
        check(gateway.maxConcurrent == 1, "no concurrent submissions");
        equal(0, gateway.loginCalls, "runner never logs in implicitly");
        equal(0, gateway.prepareCalls, "runner uses prepared activities only");
        check(!gateway.locations.get(0).isPresent(), "runner does not invent location");
    }

    private static void activityMismatch() {
        FakeGateway gateway = new FakeGateway();
        PreparedAccount other = new PreparedAccount(account("u1"), Collections.singletonList(activity("99")));
        final int[] callbacks = {0};
        List<BatchRunner.Entry> entries = new BatchRunner(gateway).run(Collections.singletonList(other), qr(), null,
                new HashSet<String>(), new BatchRunner.Listener() { public void onProgress(int n, int total, BatchRunner.Entry entry) { callbacks[0]++; }});
        equal(SignResult.State.SKIPPED, entries.get(0).result.state, "unmatched activity skipped");
        equal(0, gateway.calls.size(), "unmatched activity never submitted");
        equal(1, callbacks[0], "skipped account still reports progress");
    }

    private static void completedAndRescan() {
        FakeGateway gateway = new FakeGateway();
        gateway.states.put("u2", SignResult.State.ALREADY_SIGNED);
        Set<String> completed = new HashSet<String>();
        List<PreparedAccount> queue = queue("u1", "u2");
        BatchRunner runner = new BatchRunner(gateway);
        runner.run(queue, qr(), LocationValue.empty(), completed, null);
        check(completed.contains("u1|42"), "success key recorded");
        check(completed.contains("u2|42"), "already-signed key recorded");
        List<BatchRunner.Entry> rescan = runner.run(queue, QrPayload.parse("id=42&enc=NEW&c=000002"), LocationValue.empty(), completed, null);
        equal(2, gateway.calls.size(), "rescanning same activity does not resubmit successes");
        equal(SignResult.State.SKIPPED, rescan.get(0).result.state, "completed success skipped");
        equal(SignResult.State.SKIPPED, rescan.get(1).result.state, "completed already-signed skipped");
        runner.run(Collections.singletonList(new PreparedAccount(queue.get(0).account, Collections.singletonList(activity("43")))),
                QrPayload.parse("id=43&enc=x"), LocationValue.empty(), completed, null);
        equal(3, gateway.calls.size(), "different activity remains eligible");
    }

    private static void expiredStopsQueue() {
        FakeGateway gateway = new FakeGateway();
        gateway.states.put("u2", SignResult.State.EXPIRED);
        Set<String> completed = new HashSet<String>();
        List<PreparedAccount> queue = queue("u1", "u2", "u3", "u4");
        BatchRunner runner = new BatchRunner(gateway);
        List<BatchRunner.Entry> entries = runner.run(queue, qr(), LocationValue.empty(), completed, null);
        equal(Arrays.asList("u1", "u2"), gateway.calls, "expired code stops subsequent submits");
        equal(SignResult.State.EXPIRED, entries.get(1).result.state, "expired result retained");
        equal(SignResult.State.WAITING_NEW_CODE, entries.get(2).result.state, "third account waits for new code");
        equal(SignResult.State.WAITING_NEW_CODE, entries.get(3).result.state, "fourth account waits for new code");
        check(!completed.contains("u2|42"), "expired result not completed");
        gateway.states.remove("u2");
        runner.run(queue, QrPayload.parse("id=42&enc=NEW"), LocationValue.empty(), completed, null);
        equal(Arrays.asList("u1", "u2", "u2", "u3", "u4"), gateway.calls, "new code resumes remaining accounts only");
    }

    private static void cancellationKeepsInflightResult() {
        FakeGateway gateway = new FakeGateway();
        final BatchRunner runner = new BatchRunner(gateway);
        gateway.hook = new Runnable() { public void run() { runner.cancel(); }};
        Set<String> completed = new HashSet<String>();
        List<BatchRunner.Entry> entries = runner.run(queue("u1", "u2", "u3"), qr(), LocationValue.empty(), completed, null);
        equal(Arrays.asList("u1"), gateway.calls, "cancel stops only subsequent submissions");
        equal(SignResult.State.SUCCESS, entries.get(0).result.state, "inflight success preserved after cancel");
        equal(SignResult.State.CANCELLED, entries.get(1).result.state, "second account cancelled");
        equal(SignResult.State.CANCELLED, entries.get(2).result.state, "third account cancelled");
        check(completed.contains("u1|42"), "inflight success recorded despite cancellation");
    }

    private static void uncertainDoesNotRetry() {
        FakeGateway gateway = new FakeGateway();
        gateway.exceptions.add("u1");
        gateway.nulls.add("u2");
        gateway.states.put("u3", SignResult.State.AUTH_REQUIRED);
        gateway.states.put("u4", SignResult.State.FAILED);
        Set<String> completed = new HashSet<String>();
        List<BatchRunner.Entry> entries = new BatchRunner(gateway).run(queue("u1", "u2", "u3", "u4", "u5"), qr(), null, completed, null);
        equal(Arrays.asList("u1", "u2", "u3", "u4", "u5"), gateway.calls, "exception neither retries nor suppresses unrelated accounts");
        equal(SignResult.State.UNCERTAIN, entries.get(0).result.state, "exception mapped to uncertain");
        equal(SignResult.State.UNCERTAIN, entries.get(1).result.state, "missing response mapped to uncertain");
        equal(SignResult.State.AUTH_REQUIRED, entries.get(2).result.state, "authentication status retained");
        equal(SignResult.State.FAILED, entries.get(3).result.state, "failure retained");
        equal(Collections.singleton("u5|42"), completed, "only definite successes completed");
    }

    private static void duplicatesAreRejectedBeforeSubmission() {
        final FakeGateway gateway = new FakeGateway();
        final BatchRunner runner = new BatchRunner(gateway);
        invalid(new Runnable() { public void run() {
            runner.run(queue("u1", "u2", "u1"), qr(), null, new HashSet<String>(), null);
        }}, "duplicate uid rejected");
        equal(0, gateway.calls.size(), "duplicate validation runs before first submission");
        invalid(new Runnable() { public void run() {
            runner.run(queue(""), qr(), null, new HashSet<String>(), null);
        }}, "missing uid rejected");
        List<PreparedAccount> emptySelection = queue("u1");
        emptySelection.get(0).account.selected = false;
        equal(0, runner.run(emptySelection, qr(), null, new HashSet<String>(), null).size(), "empty selection submits nothing");
    }

    private static void cancelBeforeRunIsHonored() {
        FakeGateway gateway = new FakeGateway();
        BatchRunner runner = new BatchRunner(gateway);
        runner.cancel();
        final List<Integer> progress = new ArrayList<Integer>();
        List<BatchRunner.Entry> entries = runner.run(queue("u1", "u2"), qr(), null, new HashSet<String>(),
                new BatchRunner.Listener() { public void onProgress(int n, int total, BatchRunner.Entry entry) { progress.add(n); }});
        equal(SignResult.State.CANCELLED, entries.get(0).result.state, "cancel before worker starts is honored");
        equal(SignResult.State.CANCELLED, entries.get(1).result.state, "all later accounts cancelled");
        equal(0, gateway.calls.size(), "pre-cancelled queue makes no requests");
        equal(Arrays.asList(1, 2), progress, "cancelled entries still report progress");
        new BatchRunner(gateway).run(queue("u3"), qr(), null, new HashSet<String>(), null);
        equal(Arrays.asList("u3"), gateway.calls, "new runner can execute another batch");
    }

    private static Account account(String uid) { return new Account("phone", uid, "name", "fid", Collections.<String, String>emptyMap()); }
    private static ActivityContext activity(String id) { return new ActivityContext(id, "course", "class", "activity"); }
    private static QrPayload qr() { return QrPayload.parse("id=42&enc=TEST&c=000001"); }
    private static List<PreparedAccount> queue(String... uids) {
        List<PreparedAccount> queue = new ArrayList<PreparedAccount>();
        for (String uid : uids) queue.add(new PreparedAccount(account(uid), Collections.singletonList(activity("42"))));
        return queue;
    }
    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
    private static void equal(Object expected, Object actual, String message) {
        check(expected == null ? actual == null : expected.equals(actual), message + ": expected=" + expected + ", actual=" + actual);
    }
    private static void invalid(Runnable action, String message) {
        try { action.run(); }
        catch (IllegalArgumentException expected) { assertions++; return; }
        throw new AssertionError(message + ": expected IllegalArgumentException");
    }

    private static final class FakeGateway implements SigninGateway {
        final List<String> calls = new ArrayList<String>();
        final List<LocationValue> locations = new ArrayList<LocationValue>();
        final Map<String, SignResult.State> states = new HashMap<String, SignResult.State>();
        final Set<String> exceptions = new HashSet<String>();
        final Set<String> nulls = new HashSet<String>();
        int loginCalls;
        int prepareCalls;
        int active;
        int maxConcurrent;
        Thread owner;
        Runnable hook;

        public Account login(String phone, String password) { loginCalls++; throw new AssertionError("No login in runner tests"); }
        public List<ActivityContext> prepare(Account account) { prepareCalls++; throw new AssertionError("No prepare in runner tests"); }
        public SignResult submit(Account account, ActivityContext activity, QrPayload qr, LocationValue location) throws Exception {
            calls.add(account.uid);
            locations.add(location);
            if (owner == null) owner = Thread.currentThread();
            check(owner == Thread.currentThread(), "same worker thread for every submission");
            active++;
            maxConcurrent = Math.max(maxConcurrent, active);
            try {
                if (hook != null) hook.run();
                if (exceptions.contains(account.uid)) throw new IOException("synthetic offline failure");
                if (nulls.contains(account.uid)) return null;
                SignResult.State state = states.get(account.uid);
                return new SignResult(state == null ? SignResult.State.SUCCESS : state, "synthetic offline result");
            } finally { active--; }
        }
    }
}
