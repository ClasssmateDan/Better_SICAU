package com.dan.qrbatch;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ScrollView;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w360dp-h720dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class IntegratedUiTest {
    private ActivityController<MainActivity> controller;
    private MainActivity activity;
    @Before public void open() throws Exception {
        controller = Robolectric.buildActivity(MainActivity.class).setup(); activity = controller.get();
        long deadline = System.nanoTime() + 3_000_000_000L;
        while (!((Button)tagged("add_account")).isEnabled() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5);
        }
    }
    @After public void close() { controller.pause().stop().destroy(); }
    private View tagged(String tag) { return activity.getWindow().getDecorView().findViewWithTag(tag); }
    private void layout(int width) {
        View root = activity.getWindow().getDecorView();
        root.measure(View.MeasureSpec.makeMeasureSpec(SigninTheme.dp(activity,width), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(SigninTheme.dp(activity,720), View.MeasureSpec.EXACTLY));
        root.layout(0,0,root.getMeasuredWidth(),root.getMeasuredHeight());
    }
    private void verifyButtons(View view, int width) {
        if (view instanceof Button && view.getVisibility() == View.VISIBLE) {
            assertTrue("button must fit horizontally", view.getWidth() > 0 && view.getWidth() <= width);
            assertTrue("button needs an accessible height", view.getHeight() >= SigninTheme.dp(activity,44));
        }
        if (view instanceof ViewGroup) for (int i=0;i<((ViewGroup)view).getChildCount();i++) verifyButtons(((ViewGroup)view).getChildAt(i),width);
    }
    @Test public void narrowAndWideScreensKeepButtonsInsideThePage() {
        for (int width : new int[]{320,360,430}) {
            layout(width); verifyButtons(tagged("learningpass_root"), SigninTheme.dp(activity,width));
            View back = tagged("learningpass_back");
            assertTrue(back.isShown()); assertEquals("返回课程表",back.getContentDescription().toString());
            ScrollView content = (ScrollView)tagged("learningpass_scroll"); content.scrollTo(0,10000);
            int[] backAt = new int[2]; back.getLocationOnScreen(backAt);
            assertTrue("return action stays above scrolling content",backAt[1] < content.getTop());
        }
    }
    @Test public void nativeControlsUseTimetableColorsAndCanRender() throws Exception {
        assertEquals(0xffe87e64,SigninTheme.ACCENT); assertEquals(0xfff7f8fa,SigninTheme.BACKGROUND);
        for (int width : new int[]{320,360,430}) {
            layout(width); ((ScrollView)tagged("learningpass_scroll")).scrollTo(0,0);
            View decor = activity.getWindow().getDecorView(); Bitmap bitmap = Bitmap.createBitmap(decor.getWidth(),decor.getHeight(),Bitmap.Config.ARGB_8888);
            decor.draw(new Canvas(bitmap));
            String folder = System.getProperty("kejian.test.screenshots");
            if (folder != null) {
                File file = new File(folder,"learningpass-"+width+".png"); file.getParentFile().mkdirs();
                try (FileOutputStream out = new FileOutputStream(file)) { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out)); }
            }
            bitmap.recycle();
        }
    }
}
