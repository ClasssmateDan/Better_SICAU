package com.dan.qrbatch;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;

/** Native controls share the timetable's warm accent, surfaces and touch targets. */
final class SigninTheme {
    static final int INK = 0xff263343, MUTED = 0xff758393, ACCENT = 0xffe87e64;
    static final int BACKGROUND = 0xfff7f8fa, BORDER = 0xffe9edf1;
    static final int SUCCESS = 0xff4f806b, ERROR = 0xffb56352;

    private SigninTheme() { }
    static int dp(Context context, int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    static GradientDrawable surface(Context context, int fill, int border, int radius) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(fill); shape.setCornerRadius(dp(context, radius));
        if (border != 0) shape.setStroke(dp(context, 1), border);
        return shape;
    }
    static void button(Button button, boolean primary) {
        Context context = button.getContext();
        button.setAllCaps(false); button.setTextSize(14); button.setMinHeight(dp(context, 46)); button.setMinimumHeight(dp(context, 46));
        button.setMinWidth(0); button.setMinimumWidth(0); button.setPadding(dp(context, 10), dp(context, 10), dp(context, 10), dp(context, 10));
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled}, surface(context, primary ? 0xffe7d5ce : 0xfff7f8fa, primary ? 0 : BORDER, 8));
        states.addState(new int[]{}, surface(context, primary ? ACCENT : Color.WHITE, primary ? 0 : BORDER, 8));
        button.setBackgroundTintList(null);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22e87e64), states, null));
        button.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{primary ? 0xfff9f4f1 : 0xffa7b0bc, primary ? Color.WHITE : INK}));
        button.setStateListAnimator(null);
    }
    static void insets(View root) {
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            } else view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
    }
}
