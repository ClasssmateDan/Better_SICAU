package com.dan.kejian;

import android.app.AlertDialog;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.webkit.WebView;
import android.widget.Button;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
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

/** Native page tests use ShadowWebView and a fake probe, never an actual school request. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class SigninWindowTest {
    private ActivityController<SigninActivity> controller;
    private SigninActivity activity;
    private boolean destroyed;
    private void open() {
        open(null);
    }
    private void open(SigninRoute route) {
        String url=SigninUrl.renew("https://eol.sicau.edu.cn/Roll/qrsign?qr=OTAwMDAwMDAxLDE3OTM4ODA4MDAwMDA%3D",1793880800000L);
        Intent intent=new Intent(RuntimeEnvironment.getApplication(),SigninActivity.class).putExtra("url",url);
        controller=Robolectric.buildActivity(SigninActivity.class,intent);activity=controller.get();
        if(route!=null){try{java.lang.reflect.Field f=SigninActivity.class.getDeclaredField("route");f.setAccessible(true);f.set(activity,route);}catch(Exception e){throw new RuntimeException(e);}}
        controller.setup();
    }
    private Button button(String tag){return activity.getWindow().getDecorView().findViewWithTag(tag);}
    private WebView browser(){return activity.getWindow().getDecorView().findViewWithTag("signin-browser");}
    private void probe(Callable<String> probe) throws Exception {
        java.lang.reflect.Field field=SigninActivity.class.getDeclaredField("diagnosticProbe");field.setAccessible(true);field.set(activity,probe);
    }
    @After public void close(){if(controller!=null&&!destroyed)controller.pause().stop().destroy();}

    @Test public void openingPageKeepsWechatAgentAndNoAutomaticDiagnostics() throws Exception {
        open();AtomicInteger calls=new AtomicInteger();probe(()->{calls.incrementAndGet();return "synthetic";});
        assertEquals("MicroMessenger",browser().getSettings().getUserAgentString());
        assertTrue(SigninUrl.isAllowed(shadowOf(browser()).getLastLoadedUrl()));
        assertEquals(0,calls.get());assertNull(ShadowAlertDialog.getLatestAlertDialog());
    }
    @Test public void manualRenewUsesFreshTimeAndPreservesCourse() {
        open();long before=System.currentTimeMillis();button("signin-renew").performClick();long after=System.currentTimeMillis();
        String fresh=shadowOf(browser()).getLastLoadedUrl();assertTrue(SigninUrl.isAllowed(fresh));
        long generated=SigninUrl.generatedAt(fresh);assertTrue(generated>=before&&generated<=after);
        String payload=new String(java.util.Base64.getDecoder().decode(android.net.Uri.parse(fresh).getQueryParameter("qr")),java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(payload.startsWith("900000001,"));
    }
    @Test public void diagnosticsCopiesResultAndDoesNotReloadSignin() throws Exception {
        open();String previous=shadowOf(browser()).getLastLoadedUrl();AtomicInteger calls=new AtomicInteger();
        probe(()->{calls.incrementAndGet();return "模拟诊断：系统 DNS 113.54.15.40\n首页 HTTPS HTTP 200";});
        button("signin-diagnose").performClick();AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(!dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled()&&System.nanoTime()<deadline){shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5);}
        assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        String copied=((ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE)).getPrimaryClip().getItemAt(0).getText().toString();
        assertTrue(copied.contains("模拟诊断"));assertTrue(copied.contains("本次打开时链接距生成"));
        assertFalse(copied.contains("?qr="));assertEquals(1,calls.get());assertEquals(previous,shadowOf(browser()).getLastLoadedUrl());
    }
    @Test public void closingDuringProbeStopsWorkerAndDismissesDialog() throws Exception {
        open();CountDownLatch started=new CountDownLatch(1),stopped=new CountDownLatch(1);
        probe(()->{started.countDown();try{new CountDownLatch(1).await();return "unused";}finally{stopped.countDown();}});
        button("signin-diagnose").performClick();assertTrue(started.await(2,TimeUnit.SECONDS));
        AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();controller.pause().stop().destroy();destroyed=true;
        assertTrue(stopped.await(2,TimeUnit.SECONDS));shadowOf(Looper.getMainLooper()).idle();assertFalse(dialog.isShowing());
    }
    @Test public void renewingInvalidLinksAndOverflowNeverCreatesNavigation() {
        for(String bad:new String[]{"https://evil.example/Roll/qrsign?qr=x","javascript:alert(1)"}) {
            try{SigninUrl.renew(bad,1793880800000L);fail("invalid source accepted");}catch(IllegalArgumentException expected){}
        }
        String valid="https://eol.sicau.edu.cn/Roll/qrsign?qr=OTAwMDAwMDAxLDE3OTM4ODA4MDAwMDA%3D";
        for(long bad:new long[]{Long.MAX_VALUE,-1L,0L}){
            try{SigninUrl.renew(valid,bad);fail("invalid time accepted");}catch(IllegalArgumentException expected){}
        }
    }
    private static final class FakeRoute implements SigninRoute {
        boolean enabled,closed,waitForEnable;Runnable pending;
        public void apply(boolean value,Runnable done,java.util.function.Consumer<String> fail){enabled=value;if(value&&waitForEnable)pending=done;else done.run();}
        public String describe(){return "浏览器线路："+(enabled?"IPv4 兼容":"系统线路")+"\n";}
        public void close(){closed=true;}
    }
    @Test public void selectingIpv4WaitsForConfigurationAndRequiresManualNavigation(){
        FakeRoute route=new FakeRoute();route.waitForEnable=true;open(route);
        String previous=shadowOf(browser()).getLastLoadedUrl();
        android.widget.Switch option=activity.getWindow().getDecorView().findViewWithTag("signin-ipv4");
        assertFalse(option.isChecked());option.setChecked(true);
        assertFalse(button("signin-renew").isEnabled());assertEquals(previous,shadowOf(browser()).getLastLoadedUrl());
        route.pending.run();assertTrue(button("signin-renew").isEnabled());assertEquals(previous,shadowOf(browser()).getLastLoadedUrl());
        button("signin-renew").performClick();assertNotEquals(previous,shadowOf(browser()).getLastLoadedUrl());
    }
    @Test public void closingBeforeProxyCallbackNeverLoadsAnotherPage(){
        FakeRoute route=new FakeRoute();route.waitForEnable=true;open(route);
        android.widget.Switch option=activity.getWindow().getDecorView().findViewWithTag("signin-ipv4");option.setChecked(true);
        controller.pause().stop().destroy();destroyed=true;route.pending.run();assertTrue(route.closed);
    }
}
