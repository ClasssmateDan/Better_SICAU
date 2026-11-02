package com.dan.qrbatch.network;

import com.dan.qrbatch.core.Account;
import com.dan.qrbatch.core.ActivityContext;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Read-only requests, delays and pre-sign responses are all synthetic. No URL is opened. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class PreparationTest {
    private static Account account(String uid) {
        Map<String, String> cookies = new LinkedHashMap<>(); cookies.put("_uid", uid);
        return new Account("19900000000", uid, "测试账号", "-1", cookies);
    }
    private static final class Script implements ChaoxingClient.Transport {
        int courses = 6, invalidCourse = -1, delayMs;
        boolean duplicateActivity, duplicateCourse;
        CountDownLatch gate;
        final CountDownLatch threeStarted = new CountDownLatch(3);
        final AtomicInteger requests = new AtomicInteger(), courseCalls = new AtomicInteger(), inFlight = new AtomicInteger(), peak = new AtomicInteger();
        final Set<String> activeCourses = ConcurrentHashMap.newKeySet();
        final List<String> preSteps = Collections.synchronizedList(new ArrayList<>());
        final Set<Map<String, String>> cookieJars = Collections.newSetFromMap(Collections.synchronizedMap(new IdentityHashMap<>()));
        Runnable afterPreSign;

        @Override public ChaoxingClient.Response send(String method, String address, Map<String, String> values, Map<String, String> cookies) throws IOException {
            requests.incrementAndGet(); cookieJars.add(cookies);
            String path = URI.create(address).getPath();
            if (path.equals("/visit/courselistdata")) {
                assertEquals("POST", method); StringBuilder html = new StringBuilder("<html>");
                for (int i = 1; i <= courses; i++) html.append("<div id='course_").append(i).append('_').append(i + 100).append("'></div>");
                if (duplicateCourse) html.append("<div id='course_1_101'></div>");
                return reply(html.append("</html>").toString());
            }
            assertEquals("GET", method);
            Map<String, String> query = query(address);
            if (path.endsWith("/activelist")) {
                String course = query.get("courseId"); assertNotNull(course); assertNotNull(query.get("classId"));
                courseCalls.incrementAndGet(); int active = inFlight.incrementAndGet(); peak.accumulateAndGet(active, Math::max);
                threeStarted.countDown();
                try {
                    if (gate != null && !gate.await(4, TimeUnit.SECONDS)) throw new IOException("Synthetic request barrier timed out");
                    if (delayMs > 0) Thread.sleep(delayMs);
                    synchronized (cookies) { cookies.put("course_" + course, "synthetic"); }
                    if (Integer.parseInt(course) == invalidCourse) return reply("<html>changed</html>");
                    String id = duplicateActivity ? "9001" : "900" + course;
                    String list = activeCourses.contains(course) ? "{\"id\":\"" + id + "\",\"status\":1,\"otherId\":2,\"nameOne\":\"测试活动\"}," : "";
                    return reply("{\"data\":{\"activeList\":[" + list
                            + "{\"id\":\"8001\",\"status\":0,\"otherId\":2},{\"id\":\"8002\",\"status\":1,\"otherId\":0}]}}");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt(); throw new IOException("Synthetic query cancelled");
                } finally { inFlight.decrementAndGet(); }
            }
            if (path.equals("/newsign/preSign")) {
                assertEquals(0, inFlight.get()); preSteps.add("pre:" + query.get("activePrimaryId"));
                if (afterPreSign != null) afterPreSign.run(); return reply("ok");
            }
            if (path.equals("/pptSign/analysis")) { preSteps.add("analysis:" + query.get("aid")); return reply("code='+'SYNTHETIC_CODE'"); }
            if (path.equals("/pptSign/analysis2")) { preSteps.add("analysis2"); return reply("ok"); }
            throw new AssertionError("Unexpected endpoint, including final submission, must never be called by preparation");
        }
    }
    private static ChaoxingClient.Response reply(String body) { return new ChaoxingClient.Response(200, body, null); }
    private static Map<String, String> query(String address) {
        Map<String, String> result = new LinkedHashMap<>(); String query = URI.create(address).getRawQuery();
        if (query != null) for (String field : query.split("&")) {
            String[] pair = field.split("=", 2);
            result.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "");
        }
        return result;
    }

    @Test public void courseReadsOverlapButNeverExceedThree() throws Exception {
        Script script = new Script(); script.gate = new CountDownLatch(1); Account account = account("10001");
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<List<ActivityContext>> result = caller.submit(() -> new ChaoxingClient(script).prepare(account));
            assertTrue("Three queries must overlap", script.threeStarted.await(3, TimeUnit.SECONDS));
            assertEquals(3, script.courseCalls.get()); assertEquals(3, script.peak.get());
            script.gate.countDown(); assertTrue(result.get(4, TimeUnit.SECONDS).isEmpty());
            assertEquals(6, script.courseCalls.get()); assertEquals(3, script.peak.get()); assertEquals(0, script.inFlight.get());
            for (int i = 1; i <= 6; i++) assertEquals("synthetic", account.cookies.get("course_" + i));
        } finally { script.gate.countDown(); caller.shutdownNow(); }
    }
    @Test public void discoveryCompletesBeforeSequentialPreSignAndReportsProgress() throws Exception {
        Script script = new Script(); script.activeCourses.addAll(Arrays.asList("1", "3")); List<String> stages = new ArrayList<>();
        List<ActivityContext> result = new ChaoxingClient(script).prepare(account("10001"), stages::add, () -> false);
        assertEquals(6, script.courseCalls.get()); assertEquals(2, result.size());
        assertEquals("9001", result.get(0).activeId); assertEquals("1", result.get(0).courseId); assertEquals("101", result.get(0).classId);
        assertEquals("9003", result.get(1).activeId);
        assertEquals(Arrays.asList("pre:9001", "analysis:9001", "analysis2", "pre:9003", "analysis:9003", "analysis2"), script.preSteps);
        assertTrue(stages.contains("正在查询课程活动：6/6")); assertTrue(stages.contains("正在预签：2/2"));
    }
    @Test public void cancellationBeforeStartSendsNoRequest() {
        Script script = new Script();
        assertThrows(IOException.class, () -> new ChaoxingClient(script).prepare(account("10001"), ignored -> {}, () -> true));
        assertEquals(0, script.requests.get());
    }
    @Test public void cancellationStopsSchedulingAndDrainsInFlightQueries() throws Exception {
        Script script = new Script(); script.gate = new CountDownLatch(1); AtomicBoolean cancel = new AtomicBoolean();
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<List<ActivityContext>> result = caller.submit(() -> new ChaoxingClient(script).prepare(account("10001"), ignored -> {}, cancel::get));
            assertTrue(script.threeStarted.await(3, TimeUnit.SECONDS)); cancel.set(true); script.gate.countDown();
            ExecutionException error = assertThrows(ExecutionException.class, () -> result.get(4, TimeUnit.SECONDS));
            assertTrue(error.getCause() instanceof IOException); assertEquals(3, script.courseCalls.get());
            assertEquals(0, script.inFlight.get()); assertTrue(script.preSteps.isEmpty());
        } finally { script.gate.countDown(); caller.shutdownNow(); }
    }
    @Test public void badActivityResponseCannotProducePartialPreparedContexts() {
        Script script = new Script(); script.invalidCourse = 2; script.activeCourses.add("1");
        assertThrows(IOException.class, () -> new ChaoxingClient(script).prepare(account("10001")));
        assertTrue(script.preSteps.isEmpty()); assertEquals(0, script.inFlight.get()); assertTrue(script.peak.get() <= 3);
    }
    @Test public void cancellationBetweenPreSignStepsDoesNotStartAnalysis() {
        Script script = new Script(); script.activeCourses.add("1"); AtomicBoolean cancel = new AtomicBoolean();
        script.afterPreSign = () -> cancel.set(true);
        assertThrows(IOException.class, () -> new ChaoxingClient(script).prepare(account("10001"), ignored -> {}, cancel::get));
        assertEquals(Collections.singletonList("pre:9001"), script.preSteps);
    }
    @Test public void duplicateCoursesAndActivitiesArePreparedOnce() throws Exception {
        Script script = new Script(); script.duplicateCourse = true; script.duplicateActivity = true;
        script.activeCourses.addAll(Arrays.asList("1", "2"));
        List<ActivityContext> result = new ChaoxingClient(script).prepare(account("10001"));
        assertEquals(6, script.courseCalls.get()); assertEquals(1, result.size()); assertEquals("1", result.get(0).courseId);
        assertEquals(Arrays.asList("pre:9001", "analysis:9001", "analysis2"), script.preSteps);
    }
    @Test public void zeroCoursesOrInactiveActivitiesNeverPreSign() throws Exception {
        Script empty = new Script(); empty.courses = 0;
        assertTrue(new ChaoxingClient(empty).prepare(account("10001")).isEmpty()); assertEquals(1, empty.requests.get());
        Script inactive = new Script(); assertTrue(new ChaoxingClient(inactive).prepare(account("10001")).isEmpty());
        assertEquals(6, inactive.courseCalls.get()); assertTrue(inactive.preSteps.isEmpty());
    }
    @Test public void eachAccountRetainsItsOwnCookieJar() throws Exception {
        Script script = new Script(); ChaoxingClient client = new ChaoxingClient(script);
        Account first = account("10001"), second = account("10002"); client.prepare(first); client.prepare(second);
        assertEquals(2, script.cookieJars.size()); assertTrue(script.cookieJars.contains(first.cookies)); assertTrue(script.cookieJars.contains(second.cookies));
        assertEquals("10001", first.cookies.get("_uid")); assertEquals("10002", second.cookies.get("_uid"));
    }
    @Test public void syntheticLatencyBenchmark() throws Exception {
        Script serial = new Script(); serial.courses = 12; serial.delayMs = 60; Account account = account("10001");
        long start = System.nanoTime();
        for (int i = 1; i <= 12; i++) serial.send("GET", "https://mobilelearn.chaoxing.com/v2/apis/active/student/activelist?courseId=" + i + "&classId=" + (i + 100), null, account.cookies);
        long serialMillis = (System.nanoTime() - start) / 1_000_000;
        Script parallel = new Script(); parallel.courses = 12; parallel.delayMs = 60; start = System.nanoTime();
        assertTrue(new ChaoxingClient(parallel).prepare(account("10002")).isEmpty());
        long parallelMillis = (System.nanoTime() - start) / 1_000_000;
        assertEquals(12, parallel.courseCalls.get()); assertTrue(parallel.peak.get() <= 3);
        // Timing is observational. Overlap/cap are checked with latches above, avoiding timing-sensitive assertions.
        System.out.println("Synthetic preparation benchmark (12 courses, 60ms/request, no service calls): serial=" + serialMillis + "ms parallel=" + parallelMillis + "ms");
    }
}
