package com.simpledeep.chat;

import android.accessibilityservice.AccessibilityService;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.EnumMap;

import static com.simpledeep.chat.NativeExportSteps.Control;
import static com.simpledeep.chat.NativeExportSteps.Step;

public final class NativeExportService extends AccessibilityService {
    static final String IDLE = "idle", ARMED = "armed", RUNNING = "running", PAUSED = "paused", COPIED = "copied";
    private static final String ORIGINAL = "com.deepseek.chat";
    private static final long ARM_MS = 15 * 60_000L, RUN_MS = 15 * 60_000L, STUCK_MS = 10_000L;
    private static NativeExportService connected;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable check = this::advance;
    private Step lastClick;
    private boolean sawOriginal, finishingCopy, activeRun;
    private boolean waitingForResume;
    private NativeExportSteps.Request request;
    private ExportPauseButton pauseButton;
    private Step markedStep;
    private final Rect markedBounds = new Rect();
    private long markedAt;
    private long lastProgress, settingsReturnDeadline, copyReturnDeadline;

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("native_history_export", MODE_PRIVATE);
    }

    static void begin(Context context) {
        begin(context, false);
    }

    static void begin(Context context, boolean refreshExisting) {
        SharedPreferences state = prefs(context);
        // A second tap or Activity recreation must not restart a request already in flight.
        String current = status(context);
        if (RUNNING.equals(current) || ARMED.equals(current)) return;
        state.edit().putString("state", ARMED).putBoolean("refresh", refreshExisting).remove("copiedAt").remove("hint")
                .putInt("task", context instanceof Activity ? ((Activity) context).getTaskId() : -1)
                .putLong("deadline", System.currentTimeMillis() + ARM_MS).apply();
    }

    static void cancel(Context context) {
        prefs(context).edit().remove("state").remove("copiedAt").remove("task").remove("deadline")
                .remove("settingsPackage").remove("hint").apply();
        if (connected != null) connected.stopAssist();
    }

    static boolean isEnabled(Context context) {
        return connected != null;
    }

    static void expectSettings(Context context, Intent settings) {
        ComponentName target = settings.resolveActivity(context.getPackageManager());
        prefs(context).edit().putString("settingsPackage",
                target == null ? "com.android.settings" : target.getPackageName()).apply();
    }

    static String status(Context context) {
        SharedPreferences state = prefs(context);
        String name = state.getString("state", IDLE);
        if (ARMED.equals(name) || RUNNING.equals(name)) {
            long remaining = state.getLong("deadline", 0) - System.currentTimeMillis();
            if (remaining <= 0 || remaining > (ARMED.equals(name) ? ARM_MS : RUN_MS)) {
                state.edit().putString("state", PAUSED).apply();
                return PAUSED;
            }
        }
        return name;
    }

    static long copiedAt(Context context) {
        return prefs(context).getLong("copiedAt", 0);
    }

    static boolean resumeCopyFromHome(Activity home, int requestCode) {
        SharedPreferences state = prefs(home);
        if (!COPIED.equals(state.getString("state", IDLE)) || state.getInt("task", -1) != home.getTaskId()) return false;
        long copied = copiedAt(home);
        long age = System.currentTimeMillis() - copied;
        if (copied == 0 || age < 0 || age > 30_000 || state.getLong("returnedFromHome", 0) == copied) return false;
        // Some launchers restore the task to MainActivity instead of the migration tutorial.
        Intent tutorial = new Intent(home, HistoryTransferActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        home.startActivityForResult(tutorial, requestCode);
        state.edit().putLong("returnedFromHome", copied).apply();
        return true;
    }

    static String pausedHint(Context context) {
        return prefs(context).getString("hint", "自动迁移已停止。可以从原版主页重试，或改为手动导出。");
    }

    static boolean startInOfficialApp(Activity activity) {
        NativeExportService service = connected;
        if (service == null || !ARMED.equals(status(activity)) || !activity.hasWindowFocus()) return false;
        Intent original = activity.getPackageManager().getLaunchIntentForPackage(ORIGINAL);
        if (original == null) {
            service.pause();
            return false;
        }
        service.sawOriginal = false;
        service.finishingCopy = false;
        service.lastClick = null;
        service.lastProgress = SystemClock.elapsedRealtime();
        service.activeRun = true;
        long submittedAt = prefs(activity).getLong("submittedAt", 0);
        long age = System.currentTimeMillis() - submittedAt;
        SharedPreferences state = prefs(activity);
        service.request = new NativeExportSteps.Request(state.getBoolean("refresh", false));
        if (submittedAt != 0 && age >= 0 && age < RUN_MS) {
            service.request.submission = state.getBoolean("replacing", false) ? Step.REEXPORT : Step.EXPORT;
            service.request.oldDate = state.getLong("oldDate", 0);
            service.request.copyWithdrawn = state.getBoolean("withdrawn", false);
        }
        prefs(activity).edit().putString("state", RUNNING)
                .putLong("deadline", System.currentTimeMillis() + RUN_MS).apply();
        try {
            activity.startActivity(original);
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            service.pause();
            return false;
        }
        service.schedule(450);
        return true;
    }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        connected = this;
        if (!ARMED.equals(status(this))) {
            if (RUNNING.equals(status(this))) pause(); // Restarted or interrupted by Android.
            else stopAssist();
            return;
        }
        settingsReturnDeadline = SystemClock.elapsedRealtime() + 4_000;
        schedule(350);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (activeRun && !finishingCopy && event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && event.getPackageName() != null && ORIGINAL.contentEquals(event.getPackageName())) schedule(200);
    }

    @Override public void onInterrupt() {
        if (connected == this && activeRun) pause("系统中断了无障碍服务。请回到 deepsleep 重新开始。");
    }

    @Override public void onDestroy() {
        stopAssist();
        if (connected == this) {
            if (RUNNING.equals(status(this)) || ARMED.equals(status(this))) {
                prefs(this).edit().putString("state", PAUSED).apply();
            }
            connected = null;
        }
        super.onDestroy();
    }

    private void schedule(long delay) {
        handler.removeCallbacks(check);
        handler.postDelayed(check, delay);
    }

    private void advance() {
        String state = status(this);
        if (waitingForResume) {
            if (canResumeHere()) schedule(400);
            else stopAssist();
            return;
        }
        if (finishingCopy) {
            finishCopyHandoff(state);
            return;
        }
        if (ARMED.equals(state)) {
            if (SystemClock.elapsedRealtime() < settingsReturnDeadline && unlocked()) {
                String settings = prefs(this).getString("settingsPackage", "com.android.settings");
                String foreground = activePackage();
                if (settings.equals(foreground) || "com.android.settings".equals(foreground)) {
                    returnToTutorial();
                    schedule(700);
                    return;
                }
                if (foreground == null || foreground.equals("android") || foreground.equals("com.android.systemui")
                        || foreground.equals("com.android.permissioncontroller") || foreground.equals("com.google.android.permissioncontroller")) {
                    schedule(300); // The permission dialog can outlive binding the service.
                    return;
                }
            }
            settingsReturnDeadline = 0;
            // Only the tutorial's Start button may enter RUNNING. Until then, sleep to the deadline.
            schedule(Math.max(1, prefs(this).getLong("deadline", 0) - System.currentTimeMillis()));
            return;
        }
        if (!RUNNING.equals(state) || !activeRun || !unlocked()) {
            if (activeRun && PAUSED.equals(state) && request.refresh && request.submission == Step.REEXPORT) {
                pause("原版还没有显示可确认的新导出文件。请在原版查看生成时间，再复制最新的下载链接。");
            } else if (RUNNING.equals(state) || activeRun && PAUSED.equals(state)) pause();
            else stopAssist();
            return;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            clearPreview();
            if (stuck()) pause("暂时无法读取原版的菜单。请先打开原版主页，再回到这里重试。");
            else schedule(450);
            return;
        }
        try {
            if (!owned(root)) {
                clearPreview();
                if (sawOriginal || stuck()) pause("已离开原版 DeepSeek。需要继续时，请重新准备迁移。");
                else schedule(450); // The original app may still be starting.
                return;
            }
            if (!sawOriginal) {
                sawOriginal = true;
                pauseButton = new ExportPauseButton(this, this::handOver);
                if (!pauseButton.show()) {
                    pause("这台手机未能显示「暂停迁移」按钮。可以在原版手动导出。");
                } else {
                    pauseButton.setStatus("正在识别原版页面");
                    schedule(500);
                }
                return;
            }
            try (Probe screen = new Probe(root)) {
                boolean withdrawn = request.copyWithdrawn;
                Step next = NativeExportSteps.next(screen, request, lastClick);
                if (next != markedStep) clearPreview();
                if (!withdrawn && request.copyWithdrawn) prefs(this).edit().putBoolean("withdrawn", true).apply();
                if (NativeExportSteps.isEarlierMenu(next, lastClick)) {
                    // An outgoing screen may briefly reappear during an animation. Never click backwards.
                    if (SystemClock.elapsedRealtime() - lastProgress < 1_500) schedule(350);
                    else pause("原版已返回上一级菜单。你可以继续手动操作，或回到 deepsleep 重新准备迁移。");
                } else if (next == Step.WAIT) {
                    pauseButton.setStatus("等待原版生成导出包");
                    lastProgress = SystemClock.elapsedRealtime();
                    schedule(950);
                } else if (next == Step.FAILED) {
                    pause("原版提示导出失败或请求过于频繁，请按原版提示稍后重试。");
                } else if (next == Step.UNKNOWN || next == lastClick) {
                    String hint = screen.drawerHint();
                    String stage = hint != null ? hint : waitingForNext();
                    pauseButton.setStatus(stage);
                    if (stuck()) {
                        trace("No transition, last=" + lastClick + " next=" + next);
                        if (lastClick == Step.REEXPORT) {
                            pause("原版需要确认或尚未显示最新文件。请在原版核对提示和导出时间，再继续手动导入。");
                        } else pause("停在这一步：" + stage + "。可手动进入下一页，再回 deepsleep 重试。");
                    }
                    else schedule(550);
                } else {
                    click(screen, next);
                }
            }
        } finally {
            root.recycle();
        }
    }

    private void click(Probe screen, Step step) {
        Control target = step == Step.SETTINGS ? Control.SETTINGS_BUTTON : Control.valueOf(step.name());
        AccessibilityNodeInfo button = screen.button(target);
        if (button == null || !button.refresh() || !owned(button) || !button.isVisibleToUser() || !button.isEnabled()) {
            pause("原版的「" + buttonName(step) + "」暂时无法点按。请确认当前页面后重试。");
            return;
        }
        Rect bounds = new Rect();
        button.getBoundsInScreen(bounds);
        screen.positionPreview(target, bounds);
        if (!previewReady(step, bounds)) return;
        long beforeClick = System.currentTimeMillis();
        // Persist before clicking so a renderer or process restart cannot submit a second export.
        if (step == Step.EXPORT || step == Step.REEXPORT) {
            request.submission = step;
            request.oldDate = step == Step.REEXPORT ? screen.dateFingerprint() : 0;
            request.copyWithdrawn = false;
            if (!prefs(this).edit().putLong("submittedAt", beforeClick).putBoolean("replacing", step == Step.REEXPORT)
                    .putLong("oldDate", request.oldDate).putBoolean("withdrawn", false).commit()) {
                pause();
                return;
            }
        }
        if (!button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            trace("Unavailable click target " + step);
            pause("原版上的导出菜单暂时无法点按。可以回到原版主页重试，或手动导出。");
            return;
        }
        clearPreview();
        lastClick = step;
        pauseButton.setStatus("已点「" + buttonName(step) + "」");
        trace("Clicked " + step);
        lastProgress = SystemClock.elapsedRealtime();
        if (step == Step.COPY) {
            finishingCopy = true;
            prefs(this).edit().putString("state", COPIED).putLong("copiedAt", beforeClick)
                    .remove("submittedAt").remove("replacing").remove("oldDate").remove("withdrawn").apply();
            copyReturnDeadline = SystemClock.elapsedRealtime() + 5_000;
            schedule(350); // Give the original app time to write its clipboard entry.
        } else {
            schedule(475);
        }
    }

    private static String buttonName(Step step) {
        switch (step) {
            case SIDEBAR: return "打开侧边栏";
            case SETTINGS: return "设置或个人信息";
            case DATA: return "数据管理";
            case EXPORT_PAGE: return "导出所有历史对话";
            case EXPORT: return "导出";
            case REEXPORT: return "重新导出";
            case COPY: return "复制下载链接";
            default: throw new IllegalArgumentException("Expected an actionable export step");
        }
    }

    private String waitingForNext() {
        if (lastClick == null) return "正在找侧边栏或设置入口";
        if (lastClick == Step.SIDEBAR) return "历史列表：正在找设置或个人信息";
        if (lastClick == Step.SETTINGS) return "设置页：正在找数据管理";
        if (lastClick == Step.DATA) return "数据管理：正在找导出入口";
        return "导出页：正在等可用的导出按钮";
    }

    // The next pass obtains a fresh node. A moved or replaced target gets a new preview.
    private boolean previewReady(Step step, Rect bounds) {
        long now = SystemClock.elapsedRealtime();
        if (step != markedStep || !markedBounds.equals(bounds)) {
            if (stuck() || bounds.isEmpty() || !pauseButton.mark(bounds)) {
                pause("「" + buttonName(step) + "」的位置暂时无法标记。可手动进入下一页，再重试。");
                return false;
            }
            markedStep = step;
            markedBounds.set(bounds);
            markedAt = now;
            pauseButton.setStatus("将点「" + buttonName(step) + "」");
        }
        long remaining = 900 - (now - markedAt);
        if (remaining <= 0) return true;
        schedule(remaining);
        return false;
    }

    private void clearPreview() {
        if (markedStep != null && pauseButton != null) pauseButton.clearMark();
        markedStep = null;
    }

    private void finishCopyHandoff(String state) {
        if (!COPIED.equals(state) || !unlocked() || SystemClock.elapsedRealtime() >= copyReturnDeadline) {
            stopAssist();
            return;
        }
        String foreground = activePackage();
        if (ORIGINAL.equals(foreground)) {
            returnToTutorial();
        } else if (foreground != null && !"android".equals(foreground) && !"com.android.systemui".equals(foreground)) {
            // The tutorial has returned, or the user has switched to another app.
            stopAssist();
            return;
        }
        // A transition can briefly expose System UI or no root. Only bring the tutorial
        // forward while DeepSeek itself owns the active window.
        schedule(450);
    }

    private boolean stuck() {
        return SystemClock.elapsedRealtime() - lastProgress >= STUCK_MS;
    }

    private boolean unlocked() {
        PowerManager power = getSystemService(PowerManager.class);
        KeyguardManager lock = getSystemService(KeyguardManager.class);
        return power != null && power.isInteractive() && lock != null && !lock.isKeyguardLocked();
    }

    private boolean activePackageIs(String expected) {
        return expected.equals(activePackage());
    }

    private String activePackage() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        try {
            CharSequence name = root.getPackageName();
            return name == null ? null : name.toString();
        } finally {
            root.recycle();
        }
    }

    private void returnToTutorial() {
        int taskId = prefs(this).getInt("task", -1);
        ActivityManager manager = getSystemService(ActivityManager.class);
        if (taskId < 0 || manager == null) return;
        try {
            for (ActivityManager.AppTask task : manager.getAppTasks()) {
                if (task.getTaskInfo().id != taskId) continue;
                Intent tutorial = new Intent(this, HistoryTransferActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                task.startActivity(this, tutorial, null);
                return;
            }
        } catch (RuntimeException unavailable) {
            // The user can return to their existing tutorial in Recents.
        }
    }

    private void pause() {
        pause("自动迁移已停止。可从原版主页重试，或改为手动导出。");
    }

    private void handOver() {
        if (finishingCopy || !activeRun || !unlocked() || !activePackageIs(ORIGINAL)) {
            pause();
            return;
        }
        activeRun = false;
        waitingForResume = true;
        handler.removeCallbacksAndMessages(null);
        clearPreview();
        prefs(this).edit().putString("state", PAUSED)
                .putString("hint", "已点「暂停迁移」。可以在原版点「继续迁移」，或回到这里重新开始。").apply();
        pauseButton.showResume(this::resumeHere);
        schedule(400);
    }

    private boolean canResumeHere() {
        return PAUSED.equals(status(this)) && unlocked()
                && prefs(this).getLong("deadline", 0) > System.currentTimeMillis() && activePackageIs(ORIGINAL);
    }

    private void resumeHere() {
        if (!waitingForResume || !canResumeHere()) {
            stopAssist();
            return;
        }
        waitingForResume = false;
        lastClick = null; // The user may have changed pages while in control.
        lastProgress = SystemClock.elapsedRealtime();
        prefs(this).edit().putString("state", RUNNING).remove("hint").apply();
        activeRun = true;
        pauseButton.showRunning();
        schedule(350);
    }

    private void pause(String hint) {
        if (finishingCopy && COPIED.equals(status(this))) {
            stopAssist();
            return;
        }
        prefs(this).edit().putString("state", PAUSED).putString("hint", hint).apply();
        if (activeRun && unlocked() && activePackageIs(ORIGINAL)) {
            Toast.makeText(this, hint, Toast.LENGTH_LONG).show();
        }
        stopAssist();
    }

    private void stopAssist() {
        handler.removeCallbacksAndMessages(null);
        if (pauseButton != null) pauseButton.hide();
        pauseButton = null;
        markedStep = null;
        lastClick = null;
        request = null;
        settingsReturnDeadline = 0;
        finishingCopy = false;
        sawOriginal = false;
        activeRun = false;
        waitingForResume = false;
    }

    private static boolean owned(AccessibilityNodeInfo node) {
        return node.getPackageName() != null && ORIGINAL.contentEquals(node.getPackageName());
    }

    private static void trace(String state) {
        if (Log.isLoggable("NativeHistoryExport", Log.DEBUG)) Log.d("NativeHistoryExport", state);
    }

    @Override protected void dump(java.io.FileDescriptor fd, java.io.PrintWriter out, String[] args) {
        if (args == null || !java.util.Arrays.asList(args).contains("--export-controls")) {
            super.dump(fd, out, args);
            return;
        }
        out.println("session=" + status(this) + " hint=" + prefs(this).getString("hint", "-")
                + " last=" + lastClick + " active=" + activeRun + " unlocked=" + unlocked());
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) { out.println("export-controls: no active root"); return; }
        try {
            if (!owned(root)) { out.println("export-controls: outside original"); return; }
            try (Probe probe = new Probe(root)) {
                Step next = NativeExportSteps.next(probe, new NativeExportSteps.Request(false), lastClick);
                out.println("next=" + next + " drawer=" + probe.drawerHint());
                AccessibilityNodeInfo target = probe.button(Control.SETTINGS_BUTTON);
                if (target != null) {
                    Rect rect = new Rect(); target.getBoundsInScreen(rect);
                    out.println("settings-action=" + rect.toShortString() + " fresh=" + target.refresh());
                }
            }
        } finally { root.recycle(); }
    }

    /** Match only the original app's navigation icons and fixed menu labels in its virtual UI tree. */
    private static final class Probe implements NativeExportSteps.Screen, AutoCloseable {
        private static final int NODE_LIMIT = 320, DEPTH_LIMIT = 32;
        private enum Pass { NAVIGATION, DRAWER, DRAWER_ICONS, MENU }
        private final AccessibilityNodeInfo root;
        private final EnumMap<Control, Match> matches = new EnumMap<>(Control.class);
        private boolean iconsChecked, drawerChecked, textChecked, hasInput, progress, incomplete;
        private int remaining;
        private long exportDate;

        Probe(AccessibilityNodeInfo root) { this.root = root; }

        @Override public boolean visible(Control control) { return match(control).visible; }
        @Override public boolean fixedText(Control control) { return match(control).fixed; }
        @Override public boolean clickable(Control control) { return button(control) != null; }
        @Override public boolean progress() { return progress && !hasInput; }
        @Override public long dateFingerprint() { return exportDate; }
        AccessibilityNodeInfo button(Control control) {
            AccessibilityNodeInfo target = match(control).button;
            if (target == null) return null;
            // A broad clickable container can enclose two different rows. Its action is ambiguous.
            for (Control other : matches.keySet()) {
                if (other != control && target.equals(matches.get(other).button)) return null;
            }
            return target;
        }

        void positionPreview(Control control, Rect actionBounds) {
            Match result = match(control);
            if (!result.conflictingLabels && result.labelBounds != null && actionBounds.contains(result.labelBounds)) {
                actionBounds.set(result.labelBounds);
            }
        }

        @Override public void inspectDrawerLabels(boolean includeText) {
            if (drawerChecked) return;
            drawerChecked = true;
            inspect(includeText ? Pass.DRAWER : Pass.DRAWER_ICONS);
        }

        String drawerHint() {
            if (!drawerChecked) return null;
            if (incomplete) return "历史列表的控件过多，未能确认设置入口";
            Match entry = matches.get(Control.SETTINGS_BUTTON);
            if (entry == null) return "正在历史列表找「设置」或「个人信息」";
            if (entry.ambiguous) return "历史列表里有多个设置入口";
            if (button(Control.SETTINGS_BUTTON) == null) return "看到设置入口，尚未找到可单独点击的按钮";
            return null;
        }

        private Match match(Control control) {
            if (!iconsChecked) {
                iconsChecked = true;
                inspect(Pass.NAVIGATION);
            }
            if (!control.navigation && !textChecked) {
                textChecked = true;
                if (!hasInput && !matches.containsKey(Control.SIDEBAR) && !matches.containsKey(Control.CLOSE_SIDEBAR)
                        && (!hasButton(Control.SETTINGS_BUTTON) || matches.containsKey(Control.BACK))) {
                    inspect(Pass.MENU);
                }
            }
            Match result = matches.get(control);
            return result == null ? Match.EMPTY : result;
        }

        private boolean hasButton(Control control) {
            Match result = matches.get(control);
            return result != null && result.button != null;
        }

        private void inspect(Pass pass) {
            remaining = NODE_LIMIT;
            boolean complete = visit(root, pass, 0);
            if (!complete || pass == Pass.MENU && hasInput) {
                // If the window is too large, a second, ambiguous control could be outside our bound.
                for (Match match : matches.values()) if (match.button != null) match.button.recycle();
                matches.clear();
                hasInput = true;
                incomplete |= !complete;
            }
        }

        private boolean visit(AccessibilityNodeInfo node, Pass pass, int depth) {
            if (--remaining < 0 || depth > DEPTH_LIMIT) return false;
            if (!owned(node) || depth > 0 && !node.isVisibleToUser()) return true;
            CharSequence type = node.getClassName();
            if (node.isEditable() || node.isPassword() || "android.widget.EditText".equals(type)) {
                hasInput = true;
                return true;
            }
            if (pass != Pass.MENU && isHistoryCollection(node, type)) return true;
            if (pass != Pass.MENU && (node.isScrollable() || "android.widget.ScrollView".equals(type))) {
                if (pass == Pass.NAVIGATION) return true;
                // A ScrollView may wrap the entire drawer. Only explicit image actions are
                // eligible below it; a conversation title can contain any menu label.
                pass = Pass.DRAWER_ICONS;
            }
            if (pass == Pass.DRAWER_ICONS) {
                if (NativeExportSteps.isDrawerSettingsIcon(type, node.getContentDescription())) {
                    record(Control.SETTINGS_BUTTON, node);
                }
            } else if (pass == Pass.NAVIGATION) {
                if ("android.widget.ProgressBar".equals(type)) progress = true;
                identify(node, node.getContentDescription(), pass);
                // A toolbar button can expose its action name as text instead of a description.
                if ("android.widget.Button".equals(type) || "android.widget.ImageButton".equals(type)) {
                    CharSequence label = node.getText();
                    if (Control.SIDEBAR.matches(label)) record(Control.SIDEBAR, node);
                    if (Control.CLOSE_SIDEBAR.matches(label)) record(Control.CLOSE_SIDEBAR, node);
                    if (Control.BACK.matches(label)) record(Control.BACK, node);
                }
            } else {
                identify(node, node.getContentDescription(), pass);
                identify(node, node.getText(), pass);
                if (pass == Pass.MENU && android.os.Build.VERSION.SDK_INT >= 28) {
                    CharSequence pane = node.getPaneTitle();
                    for (Control title : PAGE_TITLES) {
                        if (title.matches(pane)) resultFor(title).fixed = true;
                    }
                }
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child == null) continue;
                try {
                    if (!visit(child, pass, depth + 1)) return false;
                } finally {
                    child.recycle();
                }
            }
            return true;
        }

        private static boolean isHistoryCollection(AccessibilityNodeInfo node, CharSequence type) {
            return node.getCollectionInfo() != null || node.getCollectionItemInfo() != null
                    || "android.widget.ListView".equals(type) || "androidx.recyclerview.widget.RecyclerView".equals(type);
        }

        private static final Control[] PAGE_TITLES = {Control.SETTINGS_TITLE, Control.DATA, Control.EXPORT_PAGE};

        private void identify(AccessibilityNodeInfo node, CharSequence label, Pass pass) {
            if (label == null) return;
            if (pass == Pass.DRAWER) {
                if (Control.SETTINGS_BUTTON.matches(label)) record(Control.SETTINGS_BUTTON, node);
                return;
            }
            for (Control control : Control.values()) {
                if (control.navigation != (pass == Pass.NAVIGATION) || !control.matches(label)) continue;
                // Only the known settings icon is meaningful without an established drawer.
                if (pass == Pass.NAVIGATION && control == Control.SETTINGS_BUTTON && !Control.SETTINGS_TITLE.matches(label)) continue;
                if (control == Control.EXPORT_DATE) exportDate = NativeExportSteps.dateFingerprint(label);
                else record(control, node);
                return;
            }
        }

        private Match resultFor(Control control) {
            Match result = matches.get(control);
            if (result == null) {
                result = new Match();
                matches.put(control, result);
            }
            result.visible = true;
            return result;
        }

        private void record(Control control, AccessibilityNodeInfo node) {
            Match result = resultFor(control);
            if (android.os.Build.VERSION.SDK_INT >= 28 && node.isHeading()) result.fixed = true;
            AccessibilityNodeInfo button = nearestButton(node);
            if (button == null) {
                if (node.isEnabled() && !node.isClickable()) result.fixed = true;
                return;
            }
            if (!node.equals(button) && !result.conflictingLabels && !result.ambiguous) {
                Rect label = new Rect();
                node.getBoundsInScreen(label);
                if (!label.isEmpty()) {
                    if (result.labelBounds == null) result.labelBounds = label;
                    else if (!result.labelBounds.equals(label)) result.conflictingLabels = true;
                }
            }
            if (result.ambiguous || button.equals(result.button)) button.recycle();
            else if (result.button == null) result.button = button;
            else {
                button.recycle();
                result.button.recycle();
                result.button = null;
                result.ambiguous = true;
            }
        }

        private static AccessibilityNodeInfo nearestButton(AccessibilityNodeInfo node) {
            AccessibilityNodeInfo current = AccessibilityNodeInfo.obtain(node);
            for (int depth = 0; current != null && depth <= 6; depth++) {
                if (!owned(current) || !current.isEnabled() || !current.isVisibleToUser()
                        || current.isScrollable() || current.isEditable() || current.isPassword()) break;
                if (current.isClickable()) return current;
                AccessibilityNodeInfo parent = current.getParent();
                current.recycle();
                current = parent;
            }
            if (current != null) current.recycle();
            return null;
        }

        @Override public void close() {
            for (Match match : matches.values()) if (match.button != null) match.button.recycle();
        }
    }

    private static final class Match {
        static final Match EMPTY = new Match();
        boolean visible, fixed, ambiguous, conflictingLabels;
        Rect labelBounds;
        AccessibilityNodeInfo button;
    }
}
