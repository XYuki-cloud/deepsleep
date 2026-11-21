package com.simpledeep.chat;

import android.accessibilityservice.AccessibilityService;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;

final class ExportPauseButton {
    private final AccessibilityService service;
    private final WindowManager windows;
    private final Runnable pause;
    private Runnable resume;
    private Button button;
    private Marker marker;
    private String status = "";

    ExportPauseButton(AccessibilityService service, Runnable pause) {
        this.service = service;
        this.windows = service.getSystemService(WindowManager.class);
        this.pause = pause;
    }

    boolean show() {
        if (button != null) return true;
        if (windows == null) return false;
        Button control = new Button(service);
        control.setText("暂停迁移");
        control.setTextColor(Color.WHITE);
        control.setTextSize(15);
        control.setMaxWidth(dp(180));
        control.setContentDescription("暂停迁移");
        control.setMinHeight(dp(48));
        control.setPadding(dp(14), dp(4), dp(14), dp(4));
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xff294e91);
        background.setCornerRadius(dp(24));
        control.setBackgroundTintList(null);
        control.setBackground(new RippleDrawable(ColorStateList.valueOf(0x44ffffff), background, null));
        control.setOnClickListener(v -> {
            if (button != v) return;
            if (resume == null) pause.run();
            else resume.run();
        });

        WindowManager.LayoutParams layout = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        layout.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        layout.x = dp(8);
        layout.setTitle("暂停自动迁移");
        try {
            windows.addView(control, layout);
            button = control;
            return true;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    void showResume(Runnable onResume) {
        if (button == null) return;
        removeMarker();
        status = "";
        resume = onResume;
        button.setContentDescription("继续迁移");
        button.setText("已暂停 · 继续迁移");
    }

    void showRunning() {
        resume = null;
        status = "";
        if (button == null) return;
        button.setContentDescription("暂停迁移");
        button.setText("暂停迁移");
    }

    void setStatus(String text) {
        if (button == null || resume != null || text.equals(status)) return;
        status = text;
        button.setText(text + "\n暂停迁移");
    }

    boolean mark(Rect screenBounds) {
        if (button == null || resume != null || screenBounds.isEmpty()) return false;
        if (marker == null) {
            Marker view = new Marker();
            view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            WindowManager.LayoutParams layout = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            layout.setTitle("迁移点击白点");
            try {
                windows.addView(view, layout);
                marker = view;
            } catch (RuntimeException unavailable) {
                return false;
            }
        }
        marker.target.set(screenBounds);
        marker.invalidate();
        return true;
    }

    void clearMark() {
        if (marker != null) {
            marker.target.setEmpty();
            marker.invalidate();
        }
    }

    private final class Marker extends View {
        final Rect target = new Rect();
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int[] origin = new int[2];

        Marker() { super(service); }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (target.isEmpty()) return;
            getLocationOnScreen(origin);
            float x = target.exactCenterX() - origin[0];
            float y = target.exactCenterY() - origin[1];
            paint.setColor(0xff263142);
            canvas.drawCircle(x, y, dp(10), paint);
            paint.setColor(Color.WHITE);
            canvas.drawCircle(x, y, dp(7), paint);
        }
    }

    private void removeMarker() {
        if (marker != null) {
            try {
                windows.removeView(marker);
            } catch (IllegalArgumentException alreadyRemoved) {
                // Android may detach the overlay when the user disables the service.
            }
            marker = null;
        }
    }

    void hide() {
        removeMarker();
        resume = null;
        status = "";
        Button previous = button;
        if (previous == null) return;
        button = null;
        try {
            windows.removeView(previous);
        } catch (IllegalArgumentException alreadyRemoved) {
            // The window can have gone away with the service or display.
        }
    }

    private int dp(int value) {
        return Math.round(value * service.getResources().getDisplayMetrics().density);
    }
}
