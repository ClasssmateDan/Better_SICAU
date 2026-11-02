package com.dan.kejian;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.os.Looper;
import android.view.View;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Exercises the timetable host and its internal destination, without any account or network. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LearningPassIntegrationTest {
    private ActivityController<MainActivity> host;
    @After public void close() { if (host != null) host.pause().stop().destroy(); }

    @Test public void nativeEntryKeepsTheTimetableInTheSameTask() throws Exception {
        host = Robolectric.buildActivity(MainActivity.class).setup(); MainActivity activity = host.get();
        java.lang.reflect.Method handle = MainActivity.class.getDeclaredMethod("handle", JSONObject.class); handle.setAccessible(true);
        handle.invoke(activity, new JSONObject().put("type", "learningpass:open"));
        Intent opened = shadowOf(activity).getNextStartedActivity();
        assertNotNull(opened); assertEquals("com.dan.qrbatch.MainActivity", opened.getComponent().getClassName());
        assertEquals(activity.getPackageName(), opened.getComponent().getPackageName());
        assertEquals(0, opened.getFlags() & (Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        assertFalse(activity.isFinishing()); assertNull(shadowOf(activity).getLastRequestedPermission());
    }

    @Test public void signinAndCameraDestinationsAreInternalOnly() throws Exception {
        android.content.Context context = org.robolectric.RuntimeEnvironment.getApplication();
        for (Class<?> destination : new Class<?>[]{com.dan.qrbatch.MainActivity.class, com.dan.qrbatch.CameraScanActivity.class}) {
            ActivityInfo info = context.getPackageManager().getActivityInfo(new android.content.ComponentName(context, destination), 0);
            assertFalse(info.exported);
        }
        String[] requested = context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.GET_PERMISSIONS).requestedPermissions;
        assertTrue(java.util.Arrays.asList(requested).contains(android.Manifest.permission.CAMERA));
        assertFalse(java.util.Arrays.asList(requested).contains(android.Manifest.permission.ACCESS_FINE_LOCATION));
        assertFalse(java.util.Arrays.asList(requested).contains(android.Manifest.permission.ACCESS_COARSE_LOCATION));
        assertFalse(java.util.Arrays.asList(requested).contains(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION));
    }

    @Test public void openingAndReturningDoNotReplaceTimetableData() throws Exception {
        android.content.Context context = org.robolectric.RuntimeEnvironment.getApplication();
        JSONObject before = new TimetableStore(context).load(); before.put("learningpassTest", "preserved");
        new TimetableStore(context).save(before);
        for (int repeat = 0; repeat < 2; repeat++) {
            ActivityController<com.dan.qrbatch.MainActivity> nativePage = Robolectric.buildActivity(com.dan.qrbatch.MainActivity.class).setup();
            View back = nativePage.get().getWindow().getDecorView().findViewWithTag("learningpass_back");
            assertNotNull(back); back.performClick(); assertTrue(nativePage.get().isFinishing());
            nativePage.pause().stop().destroy(); shadowOf(Looper.getMainLooper()).idle();
        }
        assertEquals(before.toString(), new TimetableStore(context).load().toString());
        assertEquals("preserved", new TimetableStore(context).load().getString("learningpassTest"));
    }
}
