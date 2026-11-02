package com.dan.qrbatch;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Real Android dialog/button behavior with a fake login action. No business service is contacted. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LoginDialogTest {
    private ActivityController<Activity> controller;
    private Activity activity;
    private AlertDialog dialog;
    private LoginDialog.Feedback feedback;
    private int calls;
    private String submittedPhone, submittedPassword, submittedName;

    @Before public void setup() {
        controller = Robolectric.buildActivity(Activity.class).setup(); activity = controller.get();
        dialog = LoginDialog.show(activity, null, (phone, password, realName, receiver) -> {
            calls++; submittedPhone = phone; submittedPassword = password; submittedName = realName; feedback = receiver;
        });
        shadowOf(Looper.getMainLooper()).idle();
    }
    @After public void cleanup() { dialog.dismiss(); controller.pause().stop().destroy(); }
    private EditText input(String tag) { return dialog.getWindow().getDecorView().findViewWithTag(tag); }
    private TextView status() { return dialog.getWindow().getDecorView().findViewWithTag("login_status"); }
    private void submit() {
        input("login_phone").setText(" 19900000000 "); input("login_password").setText("synthetic-password");
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
    }

    @Test public void emptyInputShowsVisibleErrorWithoutClosingOrRequesting() {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertEquals(0, calls); assertTrue(dialog.isShowing()); assertEquals(View.VISIBLE, status().getVisibility());
        assertTrue(status().getText().toString().contains("请输入")); assertNotNull(input("login_password").getError());
    }
    @Test public void clickKeepsProgressVisibleAndPreventsDuplicateLogin() {
        submit(); assertEquals(1, calls); assertEquals("19900000000", submittedPhone);
        assertEquals("synthetic-password", submittedPassword); assertTrue(dialog.isShowing());
        assertEquals(View.VISIBLE, status().getVisibility()); assertTrue(status().getText().toString().contains("正在"));
        assertEquals("", input("login_password").getText().toString()); assertFalse(input("login_phone").isEnabled());
        assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
        assertFalse(dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled());
    }
    @Test public void workerErrorIsVisibleInDialogAndAllowsRetry() throws Exception {
        submit(); Thread worker = new Thread(() -> feedback.error("登录验证失败：连接或读取超时，请检查网络后重试。"));
        worker.start(); worker.join(); shadowOf(Looper.getMainLooper()).idle();
        assertTrue(dialog.isShowing()); assertTrue(status().getText().toString().contains("超时"));
        assertTrue(input("login_password").isEnabled()); assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
        assertEquals(View.GONE, dialog.getWindow().getDecorView().findViewWithTag("login_progress").getVisibility());
    }
    @Test public void retryRequiresPasswordAgainAndSendsOneNewRequest() {
        submit(); feedback.error("账号或密码未通过验证");
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); assertEquals(1, calls);
        input("login_password").setText("another-synthetic-password");
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); assertEquals(2, calls);
        assertEquals("another-synthetic-password", submittedPassword); assertTrue(dialog.isShowing());
    }
    @Test public void profileAndSaveStagesRemainVisible() {
        submit(); feedback.progress("账号验证通过，正在读取账户资料…");
        assertTrue(status().getText().toString().contains("账户资料"));
        feedback.progress("登录成功，正在加密保存本机会话…"); assertTrue(status().getText().toString().contains("加密保存"));
        assertTrue(dialog.isShowing());
    }
    @Test public void successClosesDialogAndClearsPassword() {
        submit(); feedback.success(); assertFalse(dialog.isShowing()); assertEquals("", input("login_password").getText().toString());
    }
    @Test public void thrownStartErrorIsVisibleAndButtonRecovers() {
        dialog.dismiss();
        dialog = LoginDialog.show(activity, null, (phone, password, realName, receiver) -> { throw new IllegalStateException("synthetic"); });
        shadowOf(Looper.getMainLooper()).idle();
        submit(); assertTrue(dialog.isShowing()); assertTrue(status().getText().toString().contains("无法启动"));
        assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
    }
    @Test public void credentialsAreExcludedFromSnapshotsAndViewState() {
        assertTrue((dialog.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
        assertFalse(input("login_password").isSaveEnabled()); assertFalse(input("login_phone").isSaveEnabled());
        assertFalse(input("login_real_name").isSaveEnabled());
    }
    @Test public void optionalNameIsSubmittedAndRetainedAfterRetry() {
        input("login_real_name").setText(" 测试姓名 "); submit();
        assertEquals("测试姓名", submittedName); assertFalse(input("login_real_name").isEnabled());
        feedback.error("模拟保存失败"); assertTrue(input("login_real_name").isEnabled());
        assertEquals(" 测试姓名 ", input("login_real_name").getText().toString());
        input("login_password").setText("second-synthetic-password"); dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertEquals(2, calls); assertEquals("测试姓名", submittedName);
    }
}
