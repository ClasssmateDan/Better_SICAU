package com.dan.qrbatch.network;

import android.content.Context;
import android.location.LocationManager;
import com.dan.qrbatch.core.Account;
import com.dan.qrbatch.core.ActivityContext;
import com.dan.qrbatch.core.LocationValue;
import com.dan.qrbatch.core.QrPayload;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Inspect mock requests only, without contacting a sign-in endpoint or reading a real position. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LocationSubmissionTest {
    private static Account account(String uid) {
        Map<String, String> cookies = new LinkedHashMap<>(); cookies.put("_uid", uid);
        return new Account("19900000000", uid, "测试姓名", "-1", cookies);
    }
    private static Map<String, String> query(String address) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String pair : URI.create(address).getRawQuery().split("&")) {
            String[] parts = pair.split("=", 2);
            out.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8), URLDecoder.decode(parts.length == 2 ? parts[1] : "", StandardCharsets.UTF_8));
        }
        return out;
    }
    @Test public void everyAccountUsesThePreparedSnapshotWithoutStartingDeviceLocation() throws Exception {
        List<Map<String, String>> calls = new ArrayList<>();
        ChaoxingClient client = new ChaoxingClient((method, address, values, cookies) -> {
            assertEquals("GET", method); assertEquals("/pptSign/stuSignajax", URI.create(address).getPath());
            calls.add(query(address)); return new ChaoxingClient.Response(200, "success", null);
        });
        LocationManager manager = (LocationManager) RuntimeEnvironment.getApplication().getSystemService(Context.LOCATION_SERVICE);
        LocationValue prepared = LocationValue.of("104.456", "30.123", "测试实际地址", "600.5");
        ActivityContext activity = new ActivityContext("9001", "1", "101", "测试活动"); QrPayload qr = QrPayload.parse("id=9001&enc=SYNTHETIC_ENC");
        client.submit(account("10001"), activity, qr, prepared); client.submit(account("10002"), activity, qr, prepared);
        assertEquals(2, calls.size()); assertEquals("10001", calls.get(0).get("uid")); assertEquals("10002", calls.get(1).get("uid"));
        assertEquals(calls.get(0).get("location"), calls.get(1).get("location"));
        JSONObject encoded = new JSONObject(calls.get(0).get("location"));
        assertEquals(104.456, encoded.getDouble("longitude"), 0); assertEquals(30.123, encoded.getDouble("latitude"), 0);
        assertEquals("测试实际地址", encoded.getString("address")); assertEquals(600.5, encoded.getDouble("altitude"), 0);
        assertTrue(shadowOf(manager).getLocationUpdateListeners().isEmpty());
    }
    @Test public void noPreparedPositionDoesNotInventOrAcquireOne() throws Exception {
        List<Map<String, String>> calls = new ArrayList<>();
        ChaoxingClient client = new ChaoxingClient((method, address, values, cookies) -> {
            calls.add(query(address)); return new ChaoxingClient.Response(200, "success", null);
        });
        client.submit(account("10001"), new ActivityContext("9001", "1", "101", "测试"), QrPayload.parse("id=9001&enc=SYNTHETIC"), LocationValue.empty());
        assertFalse(calls.get(0).containsKey("location")); assertEquals("-1", calls.get(0).get("latitude"));
        LocationManager manager = (LocationManager) RuntimeEnvironment.getApplication().getSystemService(Context.LOCATION_SERVICE);
        assertTrue(shadowOf(manager).getLocationUpdateListeners().isEmpty());
    }
}
