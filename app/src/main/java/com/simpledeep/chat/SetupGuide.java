package com.simpledeep.chat;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

/** A resumable first-run checklist with one action for the current step. */
final class SetupGuide extends LinearLayout {
    enum Step {
        LOGIN(1, "连接 DeepSeek 账号", "登录并自动连接"),
        CONNECTING(1, "正在连接账号", null),
        RECONNECT(1, "需要重新连接账号", "登录并自动连接"),
        CHECKING(2, "正在查询余额", null),
        RETRY(2, "暂时查不到余额", "重新查询余额"),
        TOP_UP(2, "余额不足，需要充值", "去官网充值"),
        CHECK_CREDIT(2, "再查一次到账情况", "重新查询余额"),
        REVIEW_PAYMENT(2, "可用余额仍然不足", "查看官网充值页"),
        HISTORY(3, "把原版的聊天带过来", "迁移原版聊天记录");

        final int number;
        final String title;
        final String action;
        Step(int number, String title, String action) {
            this.number = number;
            this.title = title;
            this.action = action;
        }
    }

    private static final int BLUE = 0xff4668ea;
    private static final int INK = 0xff20232a;
    private static final int MUTED = 0xff58606d;

    SetupGuide(Context context, Step step, String detail, Runnable next, Runnable skipHistory,
               int pausedAt, Runnable togglePause) {
        super(context);
        setOrientation(VERTICAL);
        boolean paused = pausedAt != 0;
        int number = paused ? pausedAt : step.number;
        String section = new String[]{"登录账号", "查看余额", "迁移聊天记录"}[number - 1];
        LinearLayout banner = new LinearLayout(context);
        banner.setGravity(Gravity.CENTER_VERTICAL);
        banner.setBackgroundColor(0xfff2f5ff);
        banner.setPadding(dp(20), dp(4), dp(12), dp(4));
        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(VERTICAL);
        TextView state = text(paused ? "教程已暂停" : step.action == null ? "正在自动处理" : "等待你操作",
                16, INK, true);
        state.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        labels.addView(state);
        labels.addView(text("第 " + number + " 步，共 3 步 · " + section, 14, MUTED, false));
        banner.addView(labels, new LayoutParams(0, -2, 1));
        Button toggle = new Button(context, null, android.R.attr.borderlessButtonStyle);
        toggle.setText(paused ? "继续教程" : "暂停教程");
        toggle.setTextColor(BLUE);
        toggle.setTextSize(15);
        toggle.setMinHeight(dp(48));
        toggle.setOnClickListener(view -> togglePause.run());
        banner.addView(toggle, new LayoutParams(-2, -2));
        addView(banner, new LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        addView(scroll, new LayoutParams(-1, 0, 1));
        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(26), dp(24), dp(26), dp(24));
        scroll.addView(body);

        LinearLayout progress = new LinearLayout(context);
        progress.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        for (int i = 1; i <= 3; i++) {
            View segment = new View(context);
            segment.setBackground(round(i <= number ? BLUE : 0xffe9ecf3, 3));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(4), 1);
            if (i > 1) p.leftMargin = dp(6);
            progress.addView(segment, p);
        }
        body.addView(progress);
        body.addView(new View(context), new LinearLayout.LayoutParams(1, dp(12), 1));
        ImageView whale = new ImageView(context);
        whale.setImageResource(R.mipmap.ic_launcher);
        whale.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        body.addView(whale, new LinearLayout.LayoutParams(dp(72), dp(72)));
        add(body, text(paused ? section : step.title, 28, INK, true), 16);
        TextView instructions = text(paused
                ? "准备好后，点上方「继续教程」。应用会根据当前的账号和余额接着走。" : detail,
                17, MUTED, false);
        instructions.setLineSpacing(dp(4), 1);
        instructions.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        add(body, instructions, 14);
        body.addView(new View(context), new LinearLayout.LayoutParams(1, dp(28), 2));
        if (paused) return;
        if (step.action != null) {
            Button button = new Button(context);
            button.setText(step.action);
            button.setTextColor(Color.WHITE);
            button.setTextSize(17);
            button.setAllCaps(false);
            button.setBackground(new android.graphics.drawable.RippleDrawable(
                    ColorStateList.valueOf(0x33ffffff), round(BLUE, 26), null));
            button.setMinHeight(dp(52));
            button.setOnClickListener(v -> next.run());
            body.addView(button, new LinearLayout.LayoutParams(-1, -2));
            if (step == Step.HISTORY) {
                Button skip = new Button(context, null, android.R.attr.borderlessButtonStyle);
                skip.setText("跳过，直接聊天");
                skip.setTextColor(BLUE);
                skip.setMinHeight(dp(48));
                skip.setOnClickListener(v -> skipHistory.run());
                body.addView(skip, new LinearLayout.LayoutParams(-1, -2));
            }
        } else {
            ProgressBar spinner = new ProgressBar(context);
            spinner.setContentDescription(step.title);
            spinner.setIndeterminateTintList(ColorStateList.valueOf(BLUE));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(32), dp(32));
            p.gravity = Gravity.CENTER_HORIZONTAL;
            body.addView(spinner, p);
        }
    }

    private void add(LinearLayout parent, View child, int margin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(margin);
        parent.addView(child, p);
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView label = new TextView(getContext());
        label.setText(value);
        label.setTextSize(size);
        label.setTextColor(color);
        if (bold) label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return label;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
