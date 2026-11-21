package com.simpledeep.chat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipException;

public final class HistoryTransferActivity extends Activity {
    private static final int PICK = 1;
    private static final int EXPORT = 0, FILE = 1, BUSY = 2, DONE = 3, LINK = 4, AUTO = 5, HOST = 6;
    private static final String OFFICIAL_APP = "com.deepseek.chat";
    private static final int BLUE = 0xff4668ea, INK = 0xff20232a, MUTED = 0xff58606d;
    private static final String LABEL = "迁移原版聊天记录";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable checkExport = this::continueNativeExport;
    private int step;
    private String feedback = "";
    private String renderedAutoState = "";
    private boolean downloading, resumed, refreshExisting;
    private int clipboardTries;
    private DeepSeekApi.Cancellation downloadTask;
    private HistoryExportDownload.ConfirmHost pendingHost;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        step = getPreferences(MODE_PRIVATE).getInt("nextStep", EXPORT);
        if (saved != null) {
            step = saved.getInt("step", step);
            refreshExisting = saved.getBoolean("refreshExisting");
            feedback = saved.getString("feedback", "");
            if (step == BUSY || step == HOST) {
                step = step == HOST || saved.getBoolean("download") ? LINK : FILE;
                feedback = "上次导入中断。可以重试，已导入的对话会自动跳过。";
            }
        }
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Color.WHITE);
        if (Build.VERSION.SDK_INT >= 29) getWindow().setNavigationBarContrastEnforced(false);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        worker.execute(() -> HistoryArchive.clearStaging(getCacheDir()));
        render();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("step", step);
        out.putString("feedback", feedback);
        out.putBoolean("download", downloading);
        out.putBoolean("refreshExisting", refreshExisting);
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        main.post(checkExport);
    }

    @Override protected void onPause() {
        resumed = false;
        main.removeCallbacks(checkExport);
        super.onPause();
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused && resumed) main.post(checkExport);
    }

    @Override public void finish() {
        if (step == AUTO) rememberStep(EXPORT);
        NativeExportService.cancel(this);
        super.finish();
    }

    @Override protected void onDestroy() {
        main.removeCallbacks(checkExport);
        if (downloadTask != null) new Thread(downloadTask::cancel, "history-download-cancel").start();
        worker.shutdownNow();
        super.onDestroy();
    }

    private void render() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.WHITE);
        scroll.setFillViewport(true);
        if (Build.VERSION.SDK_INT >= 30) {
            scroll.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
                return WindowInsets.CONSUMED;
            });
        }
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(24), dp(4), dp(24), dp(22));
        scroll.addView(body);
        setContentView(scroll);
        Button back = button("返回", false);
        back.setOnClickListener(v -> finish());
        body.addView(back, new LinearLayout.LayoutParams(-2, dp(48)));
        add(body, label(LABEL, 26, INK, true), 16);
        if (step == EXPORT) {
            boolean installed = officialApp() != null;
            add(body, label(installed ? "从原版 App 自动迁移" : "从 DeepSeek 官网导出", 17, BLUE, true), 16);
            if (installed) {
                add(body, label("把原版账号的聊天记录导入这台手机。原版已有下载链接时会使用上次的导出包；还没有时，会申请导出。已有的本机对话会保留。", 17, INK, false), 24);
                add(body, label("请先登录原版 DeepSeek。应用会用白点标出下一次点击的位置，旁边显示正在找的入口。无障碍开启一次即可，下次点「开始迁移」继续使用。", 16, MUTED, false), 14);
                CheckBox refresh = new CheckBox(this);
                refresh.setText("包含上次导出后的新对话（会更新原版的旧下载链接）");
                refresh.setTextColor(INK);
                refresh.setTextSize(15);
                refresh.setMinHeight(dp(48));
                refresh.setChecked(refreshExisting);
                refresh.setOnCheckedChangeListener((button, checked) -> refreshExisting = checked);
                add(body, refresh, 8);
            } else {
                add(body, label("手机上未找到原版 App。请在网页版登录之前聊天的账号，点侧边栏头像 → 设置 → 数据管理 → 导出所有历史对话。下载 ZIP 后回到这里。", 17, INK, false), 24);
            }

            if (!feedback.isEmpty()) add(body, label(feedback, 15, INK, false), 12);
            spacer(body);
            if (!installed) action(body, "去 DeepSeek 官网导出", this::openWebsite, true);
            else if (NativeExportService.isEnabled(this)) action(body, "开始迁移", () -> {
                beginAutomatic();
                startAutomatic();
            }, true);
            else action(body, "去开启无障碍", this::beginAutomatic, true);
            if (installed) action(body, "手动在原版导出", this::explainManualExport, false);
            action(body, "我已下载，直接选文件", this::chooseFile, false);
        } else if (step == AUTO) {
            renderAutomatic(body);
        } else if (step == LINK) {
            add(body, label("从下载链接导入", 17, BLUE, true), 16);
            add(body, label("在原版点「侧边栏」→「设置」→「数据管理」→「导出所有历史对话」。生成后点「复制下载链接」，返回这里即可下载并导入。", 17, INK, false), 24);
            if (!feedback.isEmpty()) add(body, label(feedback, 16, INK, false), 14);
            spacer(body);
            action(body, "导入刚复制的聊天记录", this::openCopiedLink, true);
            if (officialApp() != null) action(body, "返回自动迁移", this::showExportOptions, false);
            action(body, "我已下载，直接选文件", this::chooseFile, false);
            action(body, "在原版找不到导出？去网页版", this::openWebsite, false);
        } else if (step == FILE) {
            add(body, label("把导出文件导入 deepsleep", 15, BLUE, true), 16);
            add(body, label("点下方按钮，到「下载」里选 deepseek_data 开头的 ZIP 文件，原样选择即可。", 17, INK, false), 24);
            add(body, label("导入会保留本机已有对话。旧版的 conversations.json 或解压后的单个会话 JSON 也可以选择。", 15, MUTED, false), 14);
            if (!feedback.isEmpty()) add(body, label(feedback, 16, INK, false), 18);
            spacer(body);
            action(body, "选择导出文件", this::chooseFile, true);
            action(body, "返回导出说明", this::showExportOptions, false);
        } else if (step == HOST && pendingHost != null) {
            add(body, label("确认导出文件的来源", 17, BLUE, true), 24);
            add(body, label("这次下载由 " + pendingHost.host + " 提供。确认这是刚从原版导出页面获得的文件后，继续导入。", 17, INK, false), 16);
            spacer(body);
            action(body, "确认来源并导入", () -> download(pendingHost.link, pendingHost.host), true);
            action(body, "取消", () -> { pendingHost = null; step = LINK; render(); }, false);
        } else if (step == BUSY) {
            add(body, label(downloading ? "正在下载导出包，并把聊天记录保存到这台手机…" : "正在把聊天记录保存到这台手机…", 17, INK, false), 26);
            ProgressBar progress = new ProgressBar(this);
            progress.setContentDescription("正在导入");
            body.addView(progress, new LinearLayout.LayoutParams(dp(40), dp(52)));
        } else {
            add(body, label("导入结果", 17, BLUE, true), 24);
            add(body, label(feedback, 18, INK, false), 15);
            action(body, "查看历史会话", () -> { setResult(RESULT_OK); finish(); }, true);
        }
    }

    private Intent officialApp() {
        return getPackageManager().getLaunchIntentForPackage(OFFICIAL_APP);
    }

    private void showExportOptions() {
        step = EXPORT;
        rememberStep(EXPORT);
        feedback = "";
        render();
    }

    private String automaticState() {
        String state = NativeExportService.status(this);
        return NativeExportService.ARMED.equals(state) && NativeExportService.isEnabled(this) ? "ready" : state;
    }

    private void renderAutomatic(LinearLayout body) {
        renderedAutoState = automaticState();
        String title, message, primary;
        Runnable click;
        switch (renderedAutoState) {
            case "ready":
                title = "已开启无障碍，准备好了";
                message = "点「开始迁移」后，白点会标出下一次点击的位置。想自己操作时，先点浮窗上的「暂停迁移」；要继续时再点「继续迁移」。";
                primary = "开始迁移";
                click = this::startAutomatic;
                break;
            case NativeExportService.ARMED:
                title = "先开启迁移权限";
                message = "在系统设置里点「deepsleep 原版聊天迁移」，打开开关并确认。若手机还停在设置页，请返回 deepsleep。";
                primary = "去开启无障碍";
                click = this::beginAutomatic;
                break;
            case NativeExportService.RUNNING:
            case NativeExportService.COPIED:
                title = "正在迁移";
                message = "等待原版的导出结果。";
                primary = "停止迁移";
                click = () -> { NativeExportService.cancel(this); render(); };
                break;
            default:
                title = "自动迁移已停止";
                message = NativeExportService.pausedHint(this);
                primary = "重新准备迁移";
                click = this::beginAutomatic;
        }
        add(body, label(title, 17, BLUE, true), 16);
        add(body, label(feedback.isEmpty() ? message : feedback, 17, INK, false), 24);
        spacer(body);
        action(body, primary, click, true);
        if (NativeExportService.PAUSED.equals(renderedAutoState) && getPreferences(MODE_PRIVATE).contains("officialOpened")) {
            action(body, "导入刚复制的聊天记录", () -> {
                step = LINK;
                rememberStep(LINK);
                openCopiedLink();
            }, false);
        }
        action(body, "改为手动导出", this::explainManualExport, false);
    }

    private void beginAutomatic() {
        if (officialApp() == null) {
            openWebsite();
            return;
        }
        clipboardTries = 0;
        feedback = "";
        step = AUTO;
        rememberStep(AUTO);
        getPreferences(MODE_PRIVATE).edit().remove("officialOpened").apply();
        NativeExportService.begin(this, refreshExisting);
        render();
        if (NativeExportService.isEnabled(this)) {
            continueNativeExport();
            return;
        }
        try {
            Intent settings = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            NativeExportService.expectSettings(this, settings);
            startActivity(settings);
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            NativeExportService.cancel(this);
            feedback = "这台手机无法打开无障碍设置。可以在原版手动导出。";
            render();
        }
    }

    private void startAutomatic() {
        if (step != AUTO || !resumed || !hasWindowFocus()) return;
        if (NativeExportService.startInOfficialApp(this)) {
            getPreferences(MODE_PRIVATE).edit().putLong("officialOpened", System.currentTimeMillis()).apply();
        }
        continueNativeExport();
    }

    private void continueNativeExport() {
        main.removeCallbacks(checkExport);
        if (!resumed || !hasWindowFocus() || isFinishing() || step != AUTO) return;
        String status = NativeExportService.status(this);
        if (NativeExportService.COPIED.equals(status)) {
            long copied = NativeExportService.copiedAt(this);
            long age = System.currentTimeMillis() - copied;
            Uri link = age >= 0 && age <= 30_000 ? copiedLink(copied) : null;
            if (link == null && age >= 0 && age < 30_000 && ++clipboardTries < 17) {
                main.postDelayed(checkExport, 250);
                return;
            }
            NativeExportService.cancel(this);
            step = LINK;
            rememberStep(LINK);
            if (link != null) download(link.toString(), HistoryExportDownload.ORIGINAL_EXPORT_HOST);
            else {
                feedback = "原版已复制链接。点下方按钮读取并导入，或切回原版重新复制。";
                render();
            }
        } else {
            if (!renderedAutoState.equals(automaticState())) render();
            if (NativeExportService.ARMED.equals(status) || NativeExportService.RUNNING.equals(status)) {
                main.postDelayed(checkExport, 450);
            }
        }
    }

    private void explainManualExport() {
        NativeExportService.cancel(this);
        new AlertDialog.Builder(this).setTitle("在原版找到导出入口")
                .setMessage("点左上角展开侧边栏，点下方的设置或头像，再点「数据管理」→「导出所有历史对话」。生成后点「复制下载链接」，再回到 deepsleep。")
                .setPositiveButton("打开原版", (dialog, which) -> startExport())
                .setNegativeButton("取消", null).show();
    }

    private void startExport() {
        Intent official = officialApp();
        if (official != null) {
            try {
                startActivity(official);
                getPreferences(MODE_PRIVATE).edit().putLong("officialOpened", System.currentTimeMillis()).apply();
                step = LINK;
                rememberStep(LINK);
                feedback = "";
                render();
                return;
            } catch (ActivityNotFoundException | SecurityException unavailable) {
                // The original app may have been disabled between rendering and tapping.
            }
        }
        openWebsite();
    }

    private void openCopiedLink() {
        Uri link = copiedLink(getPreferences(MODE_PRIVATE).getLong("officialOpened", Long.MAX_VALUE));
        if (link == null) {
            feedback = "没有找到这次复制的下载链接。请切回原版，在导出页面点「复制下载链接」，然后返回。";
            render();
        } else download(link.toString(), null);
    }

    private Uri copiedLink(long since) {
        try {
            ClipboardManager clipboard = getSystemService(ClipboardManager.class);
            ClipDescription description = clipboard == null ? null : clipboard.getPrimaryClipDescription();
            if (description == null || description.getTimestamp() < since) return null;
            ClipData clip = clipboard.getPrimaryClip();
            if (clip != null && clip.getItemCount() == 1 && clip.getDescription().getTimestamp() >= since) {
                ClipData.Item item = clip.getItemAt(0);
                CharSequence raw = item.getText();
                if (raw == null && item.getUri() != null) raw = item.getUri().toString();
                if (raw != null && raw.length() <= 16384) {
                    java.net.URI parsed = new java.net.URI(raw.toString().trim());
                    if ("https".equalsIgnoreCase(parsed.getScheme()) && parsed.getHost() != null
                            && parsed.getUserInfo() == null) return Uri.parse(parsed.toASCIIString());
                }
            }
        } catch (SecurityException | java.net.URISyntaxException ignored) {
            // User can still download the official file using their browser.
        }
        return null;
    }

    private void download(String link, String approvedHost) {
        step = BUSY;
        downloading = true;
        feedback = "";
        pendingHost = null;
        rememberStep(LINK);
        DeepSeekApi.Cancellation task = new DeepSeekApi.Cancellation();
        downloadTask = task;
        render();
        worker.execute(() -> {
            HistoryArchive.Result counts = new HistoryArchive.Result();
            try {
                HistoryExportDownload.read(link, approvedHost, getCacheDir(), new ConversationStore(this), counts, task);
                reportResult(counts);
            } catch (HistoryExportDownload.ConfirmHost confirmation) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    downloadTask = null;
                    pendingHost = confirmation;
                    step = HOST;
                    render();
                });
            } catch (IOException | SecurityException | ConversationStore.StorageException problem) {
                reportFailure(LINK, problem, counts);
            }
        });
    }

    private void openWebsite() {
        // Let the browser handle the official account session and its normal ZIP download.
        Intent browser = new Intent(Intent.ACTION_VIEW, Uri.parse("https://chat.deepseek.com/"))
                .addCategory(Intent.CATEGORY_BROWSABLE);
        try {
            startActivity(browser);
            step = FILE;
            rememberStep(FILE);
            feedback = "";
        } catch (ActivityNotFoundException | SecurityException e) {
            feedback = "手机上没有可打开网页的浏览器。可在电脑浏览器打开 chat.deepseek.com 导出，再把 ZIP 传到手机。";
        }
        render();
    }

    private void chooseFile() {
        NativeExportService.cancel(this);
        downloading = false;
        step = FILE;
        rememberStep(FILE);
        feedback = "";
        render();
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE);
        // Some download managers label ZIP or JSON as generic binary data.
        picker.setType("*/*");
        picker.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                DocumentsContract.buildRootUri("com.android.providers.downloads.documents", "downloads"));
        try {
            startActivityForResult(picker, PICK);
        } catch (ActivityNotFoundException | SecurityException e) {
            feedback = "系统文件选择器无法打开。请启用手机的「文件」应用，然后重试。";
            render();
        }
    }

    @Override protected void onActivityResult(int request, int code, Intent data) {
        super.onActivityResult(request, code, data);
        if (request != PICK || code != RESULT_OK || data == null || data.getData() == null || step == BUSY) return;
        Uri document = data.getData();
        if (!"content".equals(document.getScheme())) {
            finishRead(FILE, "无法读取这个文件。请在系统「文件」中选择已下载的 ZIP 或 JSON。");
            return;
        }
        step = BUSY;
        render();
        worker.execute(() -> importFile(document));
    }

    private void importFile(Uri document) {
        HistoryArchive.Result counts = new HistoryArchive.Result();
        try (InputStream input = getContentResolver().openInputStream(document)) {
            if (input == null) throw new IOException("无法读取所选文件。");
            HistoryArchive.read(input, getCacheDir(), new ConversationStore(this), counts);
            reportResult(counts);
        } catch (IOException | SecurityException | ConversationStore.StorageException e) {
            reportFailure(FILE, e, counts);
        }
    }

    private void reportResult(HistoryArchive.Result counts) {
        String text = "新增 " + counts.added + " 个对话，已存在 " + counts.duplicates + " 个。";
        if (counts.skipped > 0) text += "\n另有 " + counts.skipped + " 个为空或无法读取。";
        if (counts.added + counts.duplicates == 0) text += "\n此导出包中没有可查看的会话。";
        finishRead(DONE, text);
    }

    private void reportFailure(int retryStep, Exception e, HistoryArchive.Result counts) {
        String text = "无法读取文件。请确认下载完整，并选择 DeepSeek 官网导出的 ZIP 或会话 JSON。";
        if (e instanceof ZipException) text = "压缩包打不开。请重新下载完整的官方导出文件。";
        else if (e instanceof ConversationStore.StorageException) text = "保存失败，请检查手机剩余空间。";
        else if (e instanceof IOException && e.getMessage() != null && e.getMessage().matches("(?s).*[\\u4e00-\\u9fff].*")) text = e.getMessage();
        if (counts.added > 0) text += "\n已保存 " + counts.added + " 个对话。重试会跳过已有记录。";
        finishRead(retryStep, text);
    }

    private void finishRead(int next, String text) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            downloadTask = null;
            downloading = false;
            step = next;
            feedback = text;
            rememberStep(next == DONE ? EXPORT : next);
            render();
        });
    }

    private void rememberStep(int next) {
        getPreferences(MODE_PRIVATE).edit().putInt("nextStep", next).apply();
    }

    private void action(LinearLayout parent, String title, Runnable onClick, boolean primary) {
        Button view = button(title, primary);
        view.setOnClickListener(v -> onClick.run());
        add(parent, view, 8);
    }

    private Button button(String title, boolean primary) {
        Button button = new Button(this);
        button.setText(title);
        button.setAllCaps(false);
        button.setTextSize(16);
        button.setMinHeight(dp(48));
        button.setTextColor(primary ? Color.WHITE : BLUE);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(primary ? BLUE : Color.WHITE);
        shape.setCornerRadius(dp(24));
        button.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x224668ea), shape, null));
        button.setStateListAnimator(null);
        return button;
    }

    private TextView label(String text, int size, int color, boolean bold) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(color);
        label.setTextSize(size);
        label.setLineSpacing(dp(3), 1);
        if (bold) label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return label;
    }

    private void spacer(LinearLayout parent) {
        parent.addView(new View(this), new LinearLayout.LayoutParams(1, dp(20), 1));
    }

    private void add(LinearLayout parent, View child, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(top);
        parent.addView(child, p);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
