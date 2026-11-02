package com.dan.qrbatch;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.location.LocationManager;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import com.dan.qrbatch.core.Account;
import com.dan.qrbatch.core.ActivityContext;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Synthetic local activity state; no login, sign-in, camera or device position. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class SigninUiTest {
    private ActivityController<MainActivity> controller;
    private MainActivity activity;
    private Account account;
    @Before public void open() throws Exception {
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION);
        shadowOf((LocationManager)RuntimeEnvironment.getApplication().getSystemService(Context.LOCATION_SERVICE)).setLocationEnabled(false);
        controller=Robolectric.buildActivity(MainActivity.class).setup(); activity=controller.get();
        long deadline=System.nanoTime()+3_000_000_000L;
        while (!((Button)activity.getWindow().getDecorView().findViewWithTag("add_account")).isEnabled() && System.nanoTime()<deadline) {
            shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5);
        }
        assertTrue(((Button)activity.getWindow().getDecorView().findViewWithTag("add_account")).isEnabled());
    }
    @After public void close() {
        AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog(); if(dialog!=null)dialog.dismiss();
        controller.pause().stop().destroy();
    }
    private Button button(String label) { return findButton(activity.getWindow().getDecorView(),label); }
    private Button findButton(View view,String label) {
        if(view instanceof Button && ((Button)view).getText().toString().equals(label))return (Button)view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++) {
            Button result=findButton(((ViewGroup)view).getChildAt(i),label); if(result!=null)return result;
        }
        return null;
    }
    private Object field(String name) throws Exception {
        java.lang.reflect.Field f=MainActivity.class.getDeclaredField(name); f.setAccessible(true); return f.get(activity);
    }
    private void render() throws Exception {
        java.lang.reflect.Method method=MainActivity.class.getDeclaredMethod("renderAccounts"); method.setAccessible(true); method.invoke(activity);
    }
    @SuppressWarnings("unchecked") private void ready() throws Exception {
        account=new Account("19900000000","10001","测试账号","-1",Collections.singletonMap("_uid","10001"));
        ((SessionStore.Snapshot)field("snapshot")).accounts.add(account);
        ((Map<String,List<ActivityContext>>)field("prepared")).put(account.uid,Collections.singletonList(new ActivityContext("9001","1","101","测试活动")));
        render();
    }
    private void sources(boolean enabled) {
        for(String name:new String[]{"扫码并依次签到","选图并签到","粘贴码并签到"})assertEquals(name,enabled,button(name).isEnabled());
    }
    @Test public void locationControlsAndPermissionPromptsAreRemoved() {
        assertNull(activity.getWindow().getDecorView().findViewWithTag("read_current_location"));
        assertNull(activity.getWindow().getDecorView().findViewWithTag("prepared_location_status"));
        assertNull(button("更新本机真实定位")); assertNull(shadowOf(activity).getLastRequestedPermission());
    }
    @Test public void unpreparedAccountsCannotStartScanning() {
        sources(false); button("扫码并依次签到").performClick(); assertNull(shadowOf(activity).getNextStartedActivity());
    }
    @Test public void preparedSourcesWorkWithGpsDisabledAndLocationDenied() throws Exception {
        ready(); sources(true);
        button("扫码并依次签到").performClick();
        assertEquals(CameraScanActivity.class.getName(),shadowOf(activity).getNextStartedActivity().getComponent().getClassName());
        button("选图并签到").performClick(); assertEquals(Intent.ACTION_OPEN_DOCUMENT,shadowOf(activity).getNextStartedActivity().getAction());
        button("粘贴码并签到").performClick(); assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing());
        assertNull(shadowOf(activity).getLastRequestedPermission());
    }
    @Test public void preparedActivityDoesNotExpireAfterTwoMinutesOrReturning() throws Exception {
        ready(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(3)); sources(true);
        controller.pause().stop().start().resume(); sources(true); assertNull(shadowOf(activity).getLastRequestedPermission());
    }
    @Test public void deselectingPreparedAccountDisablesAllScanSources() throws Exception {
        ready(); sources(true); account.selected=false; render(); sources(false);
    }
}
