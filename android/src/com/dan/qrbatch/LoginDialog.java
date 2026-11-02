package com.dan.qrbatch;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

/** Keeps login progress and failures next to the credentials, including while the keyboard is open. */
final class LoginDialog {
    interface Action { void start(String phone, String password, String realName, Feedback feedback); }
    interface Feedback {
        void progress(String message);
        void success();
        void error(String message);
    }

    static AlertDialog show(Activity activity, String previousPhone, Action action) {
        int padding = Math.round(22 * activity.getResources().getDisplayMetrics().density);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(padding, padding / 3, padding, padding / 3);
        EditText phone = field(activity, "学习通手机号", InputType.TYPE_CLASS_PHONE, 32);
        phone.setTag("login_phone");
        if (previousPhone != null) phone.setText(previousPhone);
        EditText password = field(activity, "密码（只用于本次登录，不保存）",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, 256);
        password.setTag("login_password");
        content.addView(phone); content.addView(password);
        EditText realName = field(activity, "真实姓名（选填，自动读取失败时使用）", InputType.TYPE_CLASS_TEXT, 128);
        realName.setTag("login_real_name"); content.addView(realName);
        TextView hint = new TextView(activity);
        hint.setText("直接登录学习通官方服务，密码不保存。姓名优先自动读取；选填时请与学习通账户中的实际姓名一致。");
        hint.setTextSize(12); hint.setTextColor(SigninTheme.MUTED); content.addView(hint);
        ProgressBar spinner = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        spinner.setTag("login_progress"); spinner.setIndeterminate(true); spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(SigninTheme.ACCENT)); spinner.setVisibility(View.GONE);
        content.addView(spinner);
        TextView status = new TextView(activity);
        status.setTag("login_status"); status.setTextSize(14);
        status.setPadding(0, padding / 2, 0, padding / 2);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setVisibility(View.GONE); content.addView(status);
        ScrollView scroll = new ScrollView(activity); scroll.addView(content);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(previousPhone == null ? "添加账号" : "重新登录")
                .setView(scroll).setNegativeButton("取消", null)
                .setPositiveButton("登录并保存会话", null).create();
        dialog.setOnDismissListener(ignored -> password.setText(""));
        dialog.setOnShowListener(ignored -> {
            Button submit = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            Button cancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            Feedback feedback = new Feedback() {
                private void update(Runnable update) {
                    activity.runOnUiThread(() -> {
                        if (!activity.isFinishing() && !activity.isDestroyed() && dialog.isShowing()) update.run();
                    });
                }
                @Override public void progress(String message) {
                    update(() -> { status.setTextColor(SigninTheme.ACCENT); status.setText(message); status.setVisibility(View.VISIBLE); });
                }
                @Override public void success() { update(dialog::dismiss); }
                @Override public void error(String message) {
                    update(() -> {
                        spinner.setVisibility(View.GONE);
                        status.setTextColor(SigninTheme.ERROR); status.setText(message); status.setVisibility(View.VISIBLE);
                        phone.setEnabled(true); password.setEnabled(true); realName.setEnabled(true);
                        submit.setText("重试登录并保存"); submit.setEnabled(true); cancel.setEnabled(true);
                        dialog.setCancelable(true); dialog.setCanceledOnTouchOutside(false);
                        password.requestFocus();
                    });
                }
            };
            submit.setOnClickListener(v -> {
                String number = phone.getText().toString().trim(), secret = password.getText().toString();
                String suppliedName = realName.getText().toString().trim();
                if (number.isEmpty() || secret.isEmpty()) {
                    if (number.isEmpty()) phone.setError("请输入手机号");
                    if (secret.isEmpty()) password.setError("请输入密码");
                    status.setTextColor(SigninTheme.ERROR); status.setText("请输入手机号和密码"); status.setVisibility(View.VISIBLE);
                    return;
                }
                phone.setError(null); password.setError(null); password.setText("");
                InputMethodManager keyboard = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (keyboard != null) keyboard.hideSoftInputFromWindow(password.getWindowToken(), 0);
                phone.setEnabled(false); password.setEnabled(false); realName.setEnabled(false); submit.setEnabled(false); cancel.setEnabled(false);
                submit.setText("正在登录…"); spinner.setVisibility(View.VISIBLE);
                dialog.setCancelable(false); dialog.setCanceledOnTouchOutside(false);
                feedback.progress("正在连接登录服务，请稍候…");
                try { action.start(number, secret, suppliedName, feedback); }
                catch (RuntimeException error) { feedback.error("无法启动登录，请重新打开应用后重试。"); }
            });
        });
        dialog.show();
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        return dialog;
    }

    private static EditText field(Activity activity, String hint, int type, int limit) {
        EditText field = new EditText(activity); field.setHint(hint); field.setInputType(type); field.setTextSize(14);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(limit)});
        field.setSaveEnabled(false); field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        return field;
    }
}
