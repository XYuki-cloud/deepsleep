package com.simpledeep.chat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.os.SystemClock;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int PLATFORM_SETUP = 101;
    private static final int PLATFORM_TOP_UP = 104;
    private static final int FILE_PICKER = 102;
    private static final int SPEECH_INPUT = 103;
    private static final int HISTORY_TRANSFER = 105;
    private static final int TUTORIAL_HISTORY = 106;
    private static final int HISTORY_PAGE_SIZE = 40;
    private static final int MAX_FILES = 4;
    private static final int CONTEXT_MESSAGES = 60;
    private static final int MAX_PROMPT_LENGTH = 8000;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int BG = 0xffffffff;
    private static final int PAPER = 0xffffffff;
    private static final int INK = 0xff20232a;
    private static final int BLUE = 0xff4668ea;
    private static final int MUTE = 0xff5b6370;
    private static final int LINE = 0xffe2e5eb;
    private static final int TINT = 0xffeaf0ff;
    private static final int BUBBLE = 0xffeef2fc;
    private static final int ERROR = 0xff98411f;

    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final List<DeepSeekApi.Message> messages = new ArrayList<>();
    private final Map<String, List<PickedFile>> draftFiles = new HashMap<>();
    private LocalState local;
    private ConversationStore conversations;
    private ConversationStore.Conversation currentConversation;
    private List<PickedFile> selectedFiles = new ArrayList<>();
    private String apiKey;
    private String draft;
    private DeepSeekApi.Balance balance;
    private boolean brokenKey;
    private boolean importing;
    private boolean thinking;
    private boolean webSearch;
    private boolean pickingFile;
    private boolean setupPending;
    private int guidePausedAt;
    private boolean waitingToShowImportedHistory;
    private boolean reauthNeeded;
    private boolean balanceLoading;
    private boolean afterCheckout;
    private boolean checkedAfterCheckout;
    private String setupError = "";
    private DeepSeekApi.BalanceLevel balanceLevel = DeepSeekApi.BalanceLevel.UNKNOWN;
    private long lastBalanceCheck;
    private long keyPageOpenedAt;
    private boolean waitingForBrowserReturn;
    private int historyReturnRequest = HISTORY_TRANSFER;
    private int balanceRequest;

    private LinearLayout page;
    private LinearLayout chatHeading;
    private TextView balanceSummary;
    private ImageButton connectionOptions;
    private LinearLayout screenBody;
    private TextView chatTitle;
    private ScrollView chatScroll;
    private LinearLayout messageList;
    private View emptyChat;
    private EditText composer;
    private TextView thinkingButton;
    private TextView searchButton;
    private LinearLayout attachmentBar;
    private ImageButton sendButton;
    private TextView chatNotice;
    private PendingReply reply;
    private PendingReply incompleteReply;
    private TextToSpeech speech;
    private boolean speechReady;
    private String pendingSpeech;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        local = new LocalState(this);
        try {
            conversations = new ConversationStore(this);
            loadConversation(conversations.current());
        } catch (ConversationStore.StorageException e) {
            conversations = null;
            messages.addAll(local.loadChat());
            draft = local.loadDraft();
        }
        thinking = local.thinking();
        webSearch = getSharedPreferences("simpledeep", MODE_PRIVATE).getBoolean("webSearch", false);
        if (savedInstanceState != null) {
            keyPageOpenedAt = savedInstanceState.getLong("keyPageOpenedAt");
            waitingForBrowserReturn = savedInstanceState.getBoolean("waitingForBrowserReturn");
            historyReturnRequest = savedInstanceState.getInt("historyReturnRequest", HISTORY_TRANSFER);
            waitingToShowImportedHistory = savedInstanceState.getBoolean("waitingToShowImportedHistory");
        }
        try {
            apiKey = local.loadKey();
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            brokenKey = true;
        }
        setupPending = apiKey == null || local.setupPending();
        if (setupPending) guidePausedAt = getPreferences(MODE_PRIVATE).getInt("guidePausedAt", 0);
        afterCheckout = apiKey != null && local.checkoutPending();
        configureWindow();
        render();
        if (conversations == null) notice("会话存储暂不可用。重启应用后可重试。");
    }

    @Override protected void onResume() {
        super.onResume();
        refreshBalanceIfDue();
    }

    private void refreshBalanceIfDue() {
        if (guidePausedAt == 0 && apiKey != null && !importing && !balanceLoading && !reauthNeeded
                && (lastBalanceCheck == 0 || SystemClock.elapsedRealtime() - lastBalanceCheck > 60000)) refreshBalance();
    }

    @Override protected void onPause() {
        saveConversation();
        pendingSpeech = null;
        if (speech != null) speech.stop();
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putLong("keyPageOpenedAt", keyPageOpenedAt);
        outState.putBoolean("waitingForBrowserReturn", waitingForBrowserReturn);
        outState.putInt("historyReturnRequest", historyReturnRequest);
        outState.putBoolean("waitingToShowImportedHistory", waitingToShowImportedHistory);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) return;
        if (NativeExportService.resumeCopyFromHome(this, historyReturnRequest)) return;
        completePendingPaste();
    }

    private void completePendingPaste() {
        if (!waitingForBrowserReturn || guidePausedAt != 0 || !hasWindowFocus()) return;
        long openedAt = keyPageOpenedAt;
        waitingForBrowserReturn = false;
        keyPageOpenedAt = 0;
        if (openedAt != 0 && !importing) {
            String key = clipboardKey(openedAt);
            if (key != null) validateAndSave(key);
            else showKeyDialog("在输入框里粘贴刚才复制的完整密钥。", "连接账号");
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == HISTORY_TRANSFER || request == TUTORIAL_HISTORY) {
            if (result == RESULT_OK) {
                if (request == TUTORIAL_HISTORY && guidePausedAt != 0) {
                    waitingToShowImportedHistory = true;
                    return;
                }
                if (request == TUTORIAL_HISTORY && setupPending && apiKey != null) finishTutorial();
                showHistory();
            }
            return;
        }
        if (request == FILE_PICKER) {
            if (result == RESULT_OK && data != null && data.getData() != null) pickFile(data.getData());
            return;
        }
        if (request == SPEECH_INPUT) {
            if (result == RESULT_OK && data != null && composer != null && reply == null) {
                ArrayList<String> words = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                if (words != null && !words.isEmpty()) {
                    int cursor = Math.max(0, composer.getSelectionStart());
                    composer.getText().insert(cursor, words.get(0));
                }
            }
            return;
        }
        if (request == PLATFORM_TOP_UP) {
            afterCheckout = true;
            local.setCheckoutPending(true);
            checkedAfterCheckout = false;
            lastBalanceCheck = 0;
            refreshBalance();
            return;
        }
        if (request != PLATFORM_SETUP || importing) return;
        if (result == RESULT_OK && data != null && data.getBooleanExtra(PlatformActivity.RESULT_PASTE, false)) {
            waitingForBrowserReturn = true;
            return;
        }
        keyPageOpenedAt = 0;
        waitingForBrowserReturn = false;
        if (result != RESULT_OK || data == null) return;
        String key = KeyText.parse(data.getStringExtra(PlatformActivity.RESULT_KEY));
        if (key == null) showImportError("官网没有返回完整的 API 密钥。");
        else finishImport(key, null);
    }

    @Override protected void onDestroy() {
        if (reply != null) workers.execute(reply.cancellation::cancel);
        if (speech != null) speech.shutdown();
        workers.shutdown();
        super.onDestroy();
    }

    private void configureWindow() {
        Window window = getWindow();
        window.setStatusBarColor(Build.VERSION.SDK_INT >= 30 ? Color.TRANSPARENT : BG);
        window.setNavigationBarColor(Build.VERSION.SDK_INT >= 30 ? Color.TRANSPARENT : BG);
        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        if (Build.VERSION.SDK_INT >= 29) window.setNavigationBarContrastEnforced(false);
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false);
    }

    private void render() {
        page = column();
        page.setBackgroundColor(BG);
        if (Build.VERSION.SDK_INT >= 30) {
            page.setOnApplyWindowInsetsListener((view, windowInsets) -> {
                Insets safe = windowInsets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
                return WindowInsets.CONSUMED;
            });
        }
        page.setFocusableInTouchMode(true);
        chatHeading = null;
        balanceSummary = null;
        connectionOptions = null;
        chatTitle = null;
        composer = null;
        chatNotice = null;
        incompleteReply = null;
        setContentView(page);
        page.addView(makeHeader(), new LinearLayout.LayoutParams(MATCH, WRAP));
        screenBody = column();
        page.addView(screenBody, new LinearLayout.LayoutParams(MATCH, 0, 1));
        if (setupPending) showOnboarding();
        else showChat();
        page.requestFocus();
    }

    private ImageButton iconButton(int icon, String description, int background) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(icon);
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setContentDescription(description);
        button.setPadding(0, 0, 0, 0);
        button.setBackground(tapShape(background, Color.TRANSPARENT, 24));
        return button;
    }

    private View makeHeader() {
        LinearLayout header = row();
        header.setPadding(dp(10), dp(4), dp(10), dp(4));
        if (setupPending) {
            TextView name = label("deepsleep", 19, INK, true);
            name.setPadding(dp(14), 0, 0, 0);
            header.addView(name, new LinearLayout.LayoutParams(0, WRAP, 1));
            connectionOptions = iconButton(R.drawable.ic_more, "连接选项", BG);
            connectionOptions.setOnClickListener(v -> {
                PopupMenu menu = new PopupMenu(this, v);
                if (apiKey != null && !reauthNeeded && guidePausedAt == 0) {
                    menu.getMenu().add("重新自动连接账号").setOnMenuItemClickListener(item -> {
                        connectAccount();
                        return true;
                    });
                }
                menu.getMenu().add("手动导入已有 API 密钥").setOnMenuItemClickListener(item -> {
                    importFromClipboard();
                    return true;
                });
                menu.getMenu().add("迁移原版聊天记录").setOnMenuItemClickListener(item -> {
                    openHistoryTransfer();
                    return true;
                });
                menu.getMenu().add("本机历史会话").setOnMenuItemClickListener(item -> {
                    showHistory();
                    return true;
                });
                menu.show();
            });
            header.addView(connectionOptions, new LinearLayout.LayoutParams(dp(48), dp(48)));
            header.setMinimumHeight(dp(56));
            return header;
        }
        ImageButton history = iconButton(R.drawable.ic_history, "历史会话", PAPER);
        history.setOnClickListener(v -> showHistory());
        header.addView(history, new LinearLayout.LayoutParams(dp(48), dp(48)));
        chatHeading = column();
        chatHeading.setGravity(Gravity.CENTER);
        chatHeading.setMinimumHeight(dp(48));
        chatHeading.setPadding(dp(4), dp(2), dp(4), dp(2));
        chatHeading.setBackground(tapShape(PAPER, Color.TRANSPARENT, 12));
        chatHeading.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        header.addView(chatHeading, new LinearLayout.LayoutParams(0, WRAP, 1));
        chatTitle = label(activeTitle(), 17, INK, true);
        chatTitle.setSingleLine(true);
        chatTitle.setGravity(Gravity.CENTER);
        chatTitle.setEllipsize(TextUtils.TruncateAt.END);
        chatTitle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        chatHeading.addView(chatTitle, new LinearLayout.LayoutParams(MATCH, WRAP));
        balanceSummary = label("", 12, MUTE, false);
        balanceSummary.setGravity(Gravity.CENTER);
        balanceSummary.setMaxLines(2);
        balanceSummary.setEllipsize(TextUtils.TruncateAt.END);
        balanceSummary.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        chatHeading.addView(balanceSummary, new LinearLayout.LayoutParams(MATCH, WRAP));
        TextView headingTitle = chatTitle;
        TextView headingBalance = balanceSummary;
        chatHeading.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host,
                    android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setContentDescription(headingTitle.getText() + "，" + headingBalance.getText());
                if (host.isClickable()) info.setClassName("android.widget.Button");
            }
        });
        updateBalanceUi();
        ImageButton create = iconButton(R.drawable.ic_new_chat, "新对话", PAPER);
        create.setOnClickListener(v -> startConversation());
        header.addView(create, new LinearLayout.LayoutParams(dp(44), dp(48)));
        ImageButton more = iconButton(R.drawable.ic_more, "聊天菜单", PAPER);
        more.setOnClickListener(this::showMenu);
        header.addView(more, new LinearLayout.LayoutParams(dp(44), dp(48)));
        return header;
    }

    private void showOnboarding() {
        if (screenBody == null) return;
        screenBody.removeAllViews();
        connectionOptions.setVisibility(importing ? View.INVISIBLE : View.VISIBLE);
        SetupGuide.Step step;
        String detail;
        Runnable action = null;
        if (importing) {
            step = SetupGuide.Step.CONNECTING;
            detail = "正在向 DeepSeek 确认这个账号可以使用。";
        } else if (apiKey == null || reauthNeeded) {
            step = (brokenKey || reauthNeeded) ? SetupGuide.Step.RECONNECT : SetupGuide.Step.LOGIN;
            detail = setupError.isEmpty() ? (step == SetupGuide.Step.RECONNECT
                    ? "原来的连接已失效，请重新登录 DeepSeek 官网。"
                    : "在 DeepSeek 官网输入手机号和短信验证码，点「登录」。") : setupError;
            detail += " 可跳过的「绑定邮箱」提示会自动关闭。如果官网要求验证，请按提示操作，再点上方「继续」。\n\n登录后，应用会自动创建并保存 API 密钥，再查余额。聊天费用从你的开放平台余额扣除。";
            action = this::connectAccount;
        } else if (balanceLoading || lastBalanceCheck == 0) {
            step = SetupGuide.Step.CHECKING;
            detail = "账号已连接，正在向 DeepSeek 确认可用余额。";
        } else if (balanceLevel == DeepSeekApi.BalanceLevel.UNKNOWN) {
            step = SetupGuide.Step.RETRY;
            detail = "账号已经连好。请确认手机可以上网，再点下方按钮查一次。";
            action = this::refreshBalance;
        } else if (balanceLevel == DeepSeekApi.BalanceLevel.EMPTY) {
            if (afterCheckout && !checkedAfterCheckout) {
                step = SetupGuide.Step.CHECK_CREDIT;
                detail = "官网暂未显示可用余额。如果刚付过款，请稍等片刻，再点下方按钮查一次。";
                action = this::recheckCheckout;
            } else if (afterCheckout) {
                step = SetupGuide.Step.REVIEW_PAYMENT;
                detail = "如果已经付款，请到官网核对登录账号和充值记录。如果刚才取消了付款，可以在官网继续。";
                action = this::openTopUp;
            } else {
                step = SetupGuide.Step.TOP_UP;
                detail = "到官网选金额和支付方式，确认付款后回到这里，应用会自动查余额。";
                action = this::openTopUp;
            }
        } else {
            step = SetupGuide.Step.HISTORY;
            detail = "账号已连接，余额 " + balance.display + "。用过原版 DeepSeek 的话，可以把以前的对话导入这台手机，接着聊。\n\n第一次用 DeepSeek，直接点下方「跳过，直接聊天」。以后也能从历史会话里迁移。";
            action = () -> launchHistoryTransfer(TUTORIAL_HISTORY);
        }
        screenBody.addView(new SetupGuide(this, step, detail, action, this::finishTutorial,
                guidePausedAt, () -> toggleGuidePause(step)), new LinearLayout.LayoutParams(MATCH, MATCH));
    }

    private void toggleGuidePause(SetupGuide.Step step) {
        guidePausedAt = guidePausedAt == 0 ? step.number : 0;
        getPreferences(MODE_PRIVATE).edit().putInt("guidePausedAt", guidePausedAt).apply();
        if (guidePausedAt == 0 && waitingToShowImportedHistory && apiKey != null) {
            waitingToShowImportedHistory = false;
            finishTutorial();
            showHistory();
            return;
        }
        showOnboarding();
        if (guidePausedAt == 0) {
            completePendingPaste();
            refreshBalanceIfDue();
        }
    }

    private void finishTutorial() {
        local.finishSetup();
        setupPending = false;
        render();
    }

    private void showChat() {
        chatScroll = scrollView();
        chatScroll.setFillViewport(true);
        screenBody.addView(chatScroll, new LinearLayout.LayoutParams(MATCH, 0, 1));
        messageList = column();
        messageList.setPadding(dp(18), dp(12), dp(18), dp(14));
        chatScroll.addView(messageList, new ScrollView.LayoutParams(MATCH, WRAP));
        LinearLayout welcome = column();
        welcome.setGravity(Gravity.CENTER);
        ImageView whale = new ImageView(this);
        whale.setImageResource(R.mipmap.ic_launcher);
        whale.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        welcome.addView(whale, new LinearLayout.LayoutParams(dp(60), dp(60)));
        TextView greeting = label("今天想聊点什么？", 21, INK, true);
        greeting.setGravity(Gravity.CENTER);
        addWithTop(welcome, greeting, 12);
        messageList.addView(welcome, new LinearLayout.LayoutParams(MATCH, WRAP, 1));
        emptyChat = welcome;
        welcome.setVisibility(messages.isEmpty() ? View.VISIBLE : View.GONE);
        showSavedMessages(messageList, messages, true);

        LinearLayout footer = column();
        footer.setPadding(dp(16), dp(5), dp(16), dp(8));
        screenBody.addView(footer, new LinearLayout.LayoutParams(MATCH, WRAP));
        chatNotice = label("", 13, ERROR, false);
        chatNotice.setPadding(dp(8), dp(4), dp(8), dp(8));
        chatNotice.setVisibility(View.GONE);
        footer.addView(chatNotice, new LinearLayout.LayoutParams(MATCH, WRAP));

        LinearLayout inputCard = column();
        inputCard.setPadding(dp(10), dp(4), dp(10), dp(6));
        inputCard.setBackground(shape(0xfff7f8fa, LINE, 24));
        footer.addView(inputCard, new LinearLayout.LayoutParams(MATCH, WRAP));
        attachmentBar = column();
        inputCard.addView(attachmentBar, new LinearLayout.LayoutParams(MATCH, WRAP));
        updateAttachmentBar();
        composer = new EditText(this);
        composer.setTextColor(INK);
        composer.setHintTextColor(MUTE);
        composer.setTextSize(16);
        composer.setHint("给 deepsleep 发消息");
        composer.setBackgroundColor(Color.TRANSPARENT);
        composer.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        composer.setGravity(Gravity.TOP | Gravity.START);
        composer.setPadding(dp(9), dp(10), dp(9), dp(8));
        composer.setMinHeight(dp(49));
        composer.setMaxLines(5);
        composer.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MAX_PROMPT_LENGTH)});
        composer.setText(draft);
        composer.setSelection(composer.length());
        inputCard.addView(composer, new LinearLayout.LayoutParams(MATCH, WRAP));

        LinearLayout actions = row();
        inputCard.addView(actions, new LinearLayout.LayoutParams(MATCH, WRAP));
        thinkingButton = button("深度思考", 12, MUTE, PAPER, Color.TRANSPARENT, 18);
        thinkingButton.setPadding(dp(7), 0, dp(7), 0);
        thinkingButton.setOnClickListener(view -> {
            thinking = !thinking;
            local.setThinking(thinking);
            updateThinkingButton();
        });
        actions.addView(thinkingButton, new LinearLayout.LayoutParams(WRAP, dp(42)));
        updateThinkingButton();
        searchButton = button("联网搜索", 12, MUTE, PAPER, Color.TRANSPARENT, 18);
        searchButton.setPadding(dp(7), 0, dp(7), 0);
        searchButton.setOnClickListener(view -> {
            webSearch = !webSearch;
            getSharedPreferences("simpledeep", MODE_PRIVATE).edit().putBoolean("webSearch", webSearch).apply();
            updateSearchButton();
        });
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(WRAP, dp(42));
        searchParams.leftMargin = dp(6);
        actions.addView(searchButton, searchParams);
        updateSearchButton();
        actions.addView(new View(this), new LinearLayout.LayoutParams(0, dp(1), 1));
        ImageButton add = iconButton(R.drawable.ic_plus, "添加附件或语音输入", Color.TRANSPARENT);
        actions.addView(add, new LinearLayout.LayoutParams(dp(42), dp(44)));
        add.setOnClickListener(this::showComposerMenu);
        sendButton = iconButton(R.drawable.ic_send, "发送", BLUE);
        sendButton.setOnClickListener(view -> {
            if (reply == null) sendMessage();
            else stopReply();
        });
        actions.addView(sendButton, new LinearLayout.LayoutParams(dp(42), dp(42)));
        TextView caption = label("内容由 AI 生成", 11, MUTE, false);
        caption.setGravity(Gravity.CENTER);
        addWithTop(footer, caption, 4);
        chatScroll.addOnLayoutChangeListener((view, l, top, r, bottom, oldL, oldTop, oldR, oldBottom) -> {
            if (bottom - top < oldBottom - oldTop) scrollToBottom();
        });
        scrollToBottom();
    }

    private void updateThinkingButton() {
        thinkingButton.setTextColor(thinking ? BLUE : MUTE);
        thinkingButton.setBackground(tapShape(thinking ? TINT : PAPER, LINE, 21));
        thinkingButton.setSelected(thinking);
    }

    private void updateSearchButton() {
        searchButton.setTextColor(webSearch ? BLUE : MUTE);
        searchButton.setBackground(tapShape(webSearch ? TINT : PAPER, LINE, 21));
        searchButton.setSelected(webSearch);
    }

    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 5, 0, balance == null ? "查询余额" : "余额  " + balance.display + "   ·   刷新");
        menu.getMenu().add(0, 6, 1, "充值");
        menu.getMenu().add(0, 7, 2, "使用指南");
        menu.getMenu().add(0, 4, 3, "分享对话");
        menu.getMenu().add(0, 8, 4, "重新自动连接账号");
        menu.getMenu().add(0, 2, 5, "手动导入已有 API 密钥");
        menu.getMenu().add(0, 3, 6, "管理官网密钥");
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 5: refreshBalance(true); break;
                case 6: openTopUp(); break;
                case 7:
                    new AlertDialog.Builder(this).setTitle("怎么使用 deepsleep")
                            .setMessage("点底部输入框写问题，点 ↑ 发送。\n\n左上角可以找回之前的对话，右上角的写字图标可以新建对话。\n\n聊天标题下方会显示余额，点金额就能去充值。要手动刷新余额，点右上角 ···。")
                            .setPositiveButton("知道了", null).show();
                    break;
                case 4: shareConversation(); break;
                case 3: openPlatform(PlatformActivity.MODE_KEYS); break;
                case 2: showKeyDialog("", "手动导入已有 API 密钥"); break;
                case 8: connectAccount(); break;
            }
            return true;
        });
        menu.show();
    }

    private void loadConversation(ConversationStore.Conversation conversation) {
        List<DeepSeekApi.Message> restored = new ArrayList<>(conversation.messages);
        currentConversation = conversation;
        messages.clear();
        messages.addAll(restored);
        draft = conversation.draft;
        selectedFiles = draftFiles.computeIfAbsent(conversation.id, id -> new ArrayList<>());
    }

    private String activeTitle() {
        return currentConversation == null ? "对话" : currentConversation.title;
    }

    private boolean canChangeConversation() {
        if (reply != null || importing || pickingFile) {
            notice("请先完成当前操作。");
            return false;
        }
        if (conversations == null) {
            notice("会话存储暂不可用，请重启应用。");
            return false;
        }
        return saveConversation();
    }

    private void startConversation() {
        if (!canChangeConversation()) return;
        try {
            if (!hasConversationContent() && "新对话".equals(activeTitle())) return;
            loadConversation(conversations.startNew());
            render();
        } catch (ConversationStore.StorageException e) {
            notice("无法新建会话，请重试。");
        }
    }

    private boolean hasConversationContent() {
        return !messages.isEmpty() || !draft.trim().isEmpty() || !selectedFiles.isEmpty() || incompleteReply != null;
    }

    private void openHistoryTransfer() {
        if (!canChangeConversation()) return;
        launchHistoryTransfer(HISTORY_TRANSFER);
    }

    private void launchHistoryTransfer(int request) {
        historyReturnRequest = request;
        startActivityForResult(new Intent(this, HistoryTransferActivity.class), request);
    }

    private void showHistory() {
        if (!canChangeConversation()) return;
        android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = column();
        panel.setBackgroundColor(PAPER);
        panel.setPadding(dp(14), dp(10), dp(10), dp(12));
        panel.setFocusableInTouchMode(true);
        LinearLayout controls = row();
        panel.addView(controls, new LinearLayout.LayoutParams(MATCH, WRAP));
        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setTextSize(15);
        search.setTextColor(INK);
        search.setHintTextColor(MUTE);
        search.setHint("搜索会话");
        search.setBackground(shape(0xfff4f5f7, Color.TRANSPARENT, 16));
        search.setPadding(dp(13), 0, dp(10), 0);
        android.graphics.drawable.Drawable magnifier = getDrawable(android.R.drawable.ic_menu_search).mutate();
        magnifier.setTint(MUTE);
        magnifier.setBounds(0, 0, dp(19), dp(19));
        search.setCompoundDrawablesRelative(magnifier, null, null, null);
        search.setCompoundDrawablePadding(dp(7));
        controls.addView(search, new LinearLayout.LayoutParams(0, dp(48), 1));
        TextView close = button("×", 23, MUTE, PAPER, Color.TRANSPARENT, 24);
        close.setContentDescription("关闭历史会话");
        close.setPadding(0, 0, 0, 0);
        close.setOnClickListener(v -> dialog.dismiss());
        controls.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));

        TextView recent = label("最近对话", 13, MUTE, false);
        recent.setPadding(dp(10), dp(19), dp(10), dp(7));
        panel.addView(recent, new LinearLayout.LayoutParams(MATCH, WRAP));
        ListView list = new ListView(this);
        list.setDivider(null);
        list.setItemsCanFocus(true);
        list.setVerticalScrollBarEnabled(false);
        panel.addView(list, new LinearLayout.LayoutParams(MATCH, 0, 1));
        TextView empty = label("正在读取历史会话…", 14, MUTE, false);
        empty.setGravity(Gravity.CENTER);
        empty.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        panel.addView(empty, new LinearLayout.LayoutParams(MATCH, 0, 1));
        list.setEmptyView(empty);
        TextView transfer = button("迁移原版聊天记录", 15, BLUE, PAPER, LINE, 16);
        transfer.setOnClickListener(v -> {
            dialog.dismiss();
            openHistoryTransfer();
        });
        panel.addView(transfer, new LinearLayout.LayoutParams(MATCH, dp(48)));
        if (!setupPending) {
            TextView create = button("新对话", 15, BLUE, TINT, Color.TRANSPARENT, 16);
            create.setOnClickListener(v -> {
                dialog.dismiss();
                startConversation();
            });
            addWithTop(panel, create, 6, dp(48));
        }
        HistoryRows adapter = new HistoryRows(dialog, empty);
        list.setAdapter(adapter);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                boolean searching = !s.toString().trim().isEmpty();
                recent.setText(searching ? "搜索结果" : "最近对话");
                adapter.filter(s.toString());
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        dialog.setContentView(panel);
        dialog.setCanceledOnTouchOutside(true);
        Window window = dialog.getWindow();
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setGravity(Gravity.START | Gravity.TOP);
        window.setDimAmount(0.26f);
        window.setWindowAnimations(0);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        panel.requestFocus();
        View viewport = getWindow().getDecorView();
        Runnable resize = () -> window.setLayout(Math.min(dp(360), Math.round(viewport.getWidth() * 0.85f)), MATCH);
        View.OnLayoutChangeListener resizeOnRotation = (v, l, t, r, b, oldL, oldT, oldR, oldB) -> {
            if (r - l != oldR - oldL) resize.run();
        };
        viewport.addOnLayoutChangeListener(resizeOnRotation);
        dialog.setOnDismissListener(box -> viewport.removeOnLayoutChangeListener(resizeOnRotation));
        dialog.show();
        adapter.refresh("");
        resize.run();
        if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
            int direction = panel.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? 1 : -1;
            panel.setTranslationX(direction * window.getAttributes().width);
            panel.animate().translationX(0).setDuration(180).start();
        }
    }

    private final class HistoryRows extends BaseAdapter {
        private final android.app.Dialog dialog;
        private final TextView empty;
        private List<ConversationStore.Conversation> all;
        private final List<ConversationStore.Conversation> rows = new ArrayList<>();
        private String query = "";
        private int refreshGeneration;

        HistoryRows(android.app.Dialog dialog, TextView empty) {
            this.dialog = dialog;
            this.empty = empty;
        }

        void refresh(String term) {
            query = term;
            int generation = ++refreshGeneration;
            workers.execute(() -> {
                try {
                    List<ConversationStore.Conversation> saved = conversations.list();
                    runOnUiThread(() -> {
                        if (!dialog.isShowing() || generation != refreshGeneration || isDestroyed()) return;
                        all = saved;
                        filter(query);
                    });
                } catch (ConversationStore.StorageException e) {
                    runOnUiThread(() -> {
                        if (!dialog.isShowing() || generation != refreshGeneration || isDestroyed()) return;
                        dialog.dismiss();
                        notice("无法读取历史会话，请重试。");
                    });
                }
            });
        }

        void filter(String term) {
            query = term;
            if (all == null) return;
            String match = term.trim().toLowerCase(Locale.ROOT);
            empty.setText(match.isEmpty() ? "暂无历史会话" : "没有找到匹配的会话");
            rows.clear();
            for (ConversationStore.Conversation thread : all) {
                if (thread.title.toLowerCase(Locale.ROOT).contains(match)
                        || thread.preview.toLowerCase(Locale.ROOT).contains(match)) rows.add(thread);
            }
            notifyDataSetChanged();
        }

        @Override public int getCount() { return rows.size(); }
        @Override public ConversationStore.Conversation getItem(int i) { return rows.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int i, View reuse, ViewGroup parent) {
            ConversationStore.Conversation thread = getItem(i);
            LinearLayout item = row();
            boolean selected = thread.id.equals(currentConversation.id);
            item.setBackground(shape(selected ? TINT : PAPER, Color.TRANSPARENT, 13));
            LinearLayout summary = column();
            summary.setMinimumHeight(dp(48));
            summary.setGravity(Gravity.CENTER_VERTICAL);
            summary.setPadding(dp(11), dp(6), dp(3), dp(6));
            summary.setBackground(tapShape(Color.TRANSPARENT, Color.TRANSPARENT, 13));
            summary.setFocusable(true);
            summary.setSelected(selected);
            summary.setOnClickListener(view -> {
                try {
                    loadConversation(conversations.select(thread.id));
                    dialog.dismiss();
                    if (setupPending) showOfflineConversation();
                    else render();
                } catch (ConversationStore.StorageException e) {
                    notice("无法打开会话，请重试。");
                }
            });
            TextView title = label(thread.title, 15, selected ? BLUE : INK, selected);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            summary.addView(title);
            String date = java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT).format(new Date(thread.updatedAt));
            String description = thread.title + "，" + date;
            if (!query.trim().isEmpty() && !thread.preview.isEmpty()) {
                TextView preview = label(thread.preview, 12, MUTE, false);
                preview.setSingleLine(true);
                preview.setEllipsize(TextUtils.TruncateAt.END);
                summary.addView(preview);
                description += "，" + thread.preview;
            }
            summary.setContentDescription(description);
            item.addView(summary, new LinearLayout.LayoutParams(0, WRAP, 1));
            TextView more = button("···", 20, MUTE, Color.TRANSPARENT, Color.TRANSPARENT, 14);
            more.setContentDescription(thread.title + "的管理菜单");
            more.setPadding(0, 0, 0, 0);
            more.setOnClickListener(view -> {
                PopupMenu menu = new PopupMenu(MainActivity.this, view);
                menu.getMenu().add(0, 1, 0, "重命名");
                menu.getMenu().add(0, 2, 1, "删除");
                menu.setOnMenuItemClickListener(option -> {
                    if (option.getItemId() == 1) renameConversation(thread, this);
                    else deleteConversation(thread, this);
                    return true;
                });
                menu.show();
            });
            item.addView(more, new LinearLayout.LayoutParams(dp(48), dp(48)));
            return item;
        }
    }

    private void renameConversation(ConversationStore.Conversation thread, HistoryRows rows) {
        EditText title = new EditText(this);
        title.setSingleLine(true);
        title.setFilters(new InputFilter[]{new InputFilter.LengthFilter(80)});
        title.setText(thread.title);
        title.selectAll();
        LinearLayout form = column();
        form.setPadding(dp(24), 0, dp(24), 0);
        form.addView(title);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("重命名会话").setView(form)
                .setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String value = title.getText().toString().trim();
            if (value.isEmpty()) { title.setError("请输入会话名称"); return; }
            try {
                conversations.rename(thread, value);
                currentConversation = conversations.current();
                if (chatTitle != null) chatTitle.setText(activeTitle());
                rows.refresh(rows.query);
                dialog.dismiss();
            } catch (ConversationStore.StorageException e) {
                notice("无法保存名称，请重试。");
            }
        });
    }

    private void deleteConversation(ConversationStore.Conversation thread, HistoryRows rows) {
        new AlertDialog.Builder(this).setTitle("删除此会话？").setMessage(thread.title)
                .setNegativeButton("取消", null).setPositiveButton("删除", (dialog, which) -> {
                    try {
                        boolean active = thread.id.equals(currentConversation.id);
                        ConversationStore.Conversation next = conversations.delete(thread);
                        draftFiles.remove(thread.id);
                        if (active) {
                            loadConversation(next);
                            rows.dialog.dismiss();
                            render();
                        } else rows.refresh(rows.query);
                    } catch (ConversationStore.StorageException e) {
                        notice("无法删除会话，请重试。");
                    }
                }).show();
    }

    private void shareConversation() {
        if (messages.isEmpty()) { notice("当前会话还没有消息。"); return; }
        StringBuilder transcript = new StringBuilder();
        for (DeepSeekApi.Message message : messages) {
            transcript.append("user".equals(message.role) ? "我\n" : "AI 回复\n")
                    .append(message.content == null ? "" : message.content).append("\n\n");
        }
        if (transcript.length() > 60_000) {
            notice("对话较长，本次分享最近的内容。");
            transcript.delete(0, transcript.length() - 60_000);
        }
        shareText(transcript.toString());
    }

    private void showComposerMenu(View anchor) {
        if (reply != null || pickingFile || importing) { notice("请先完成当前操作。"); return; }
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "添加图片或文件");
        menu.getMenu().add(0, 2, 1, "语音输入");
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                if (selectedFiles.size() >= MAX_FILES) { notice("每条消息最多添加四个附件。"); return true; }
                Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                        .addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    startActivityForResult(picker, FILE_PICKER);
                } catch (ActivityNotFoundException e) {
                    notice("手机上没有可用的文件选择器。");
                }
            } else startSpeechInput();
            return true;
        });
        menu.show();
    }

    private void pickFile(Uri uri) {
        if (pickingFile || reply != null || selectedFiles.size() >= MAX_FILES) return;
        if (!"content".equals(uri.getScheme())) { notice("请用系统文件选择器添加附件。"); return; }
        for (PickedFile file : selectedFiles) if (file.uri.equals(uri)) return;
        pickingFile = true;
        workers.execute(() -> {
            try {
                String name = "附件";
                long length = -1;
                try (Cursor item = getContentResolver().query(uri,
                        new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
                    if (item != null && item.moveToFirst()) {
                        int nameColumn = item.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        int sizeColumn = item.getColumnIndex(OpenableColumns.SIZE);
                        if (nameColumn >= 0 && !item.isNull(nameColumn)) name = item.getString(nameColumn);
                        if (sizeColumn >= 0 && !item.isNull(sizeColumn)) length = item.getLong(sizeColumn);
                    }
                }
                String mime = getContentResolver().getType(uri);
                DeepSeekFiles.validate(name, mime, length);
                PickedFile file = new PickedFile(uri, name, mime, length, DeepSeekFiles.isImage(name, mime));
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    pickingFile = false;
                    selectedFiles.add(file);
                    updateAttachmentBar();
                });
            } catch (IOException | RuntimeException e) {
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    pickingFile = false;
                    notice(e instanceof IOException ? e.getMessage() : "无法读取这个附件，请重新选择。");
                });
            }
        });
    }

    private void updateAttachmentBar() {
        attachmentBar.removeAllViews();
        attachmentBar.setVisibility(selectedFiles.isEmpty() ? View.GONE : View.VISIBLE);
        if (selectedFiles.isEmpty()) return;
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout items = row();
        scroll.addView(items);
        attachmentBar.addView(scroll, new LinearLayout.LayoutParams(MATCH, WRAP));
        for (PickedFile file : selectedFiles) {
            LinearLayout chip = row();
            chip.setPadding(dp(10), 0, 0, 0);
            chip.setBackground(shape(TINT, Color.TRANSPARENT, 13));
            TextView title = label(file.name, 13, BLUE, false);
            title.setSingleLine(true);
            title.setMaxWidth(dp(155));
            title.setEllipsize(TextUtils.TruncateAt.END);
            chip.addView(title);
            TextView remove = button("×", 18, MUTE, TINT, Color.TRANSPARENT, 13);
            remove.setPadding(0, 0, 0, 0);
            remove.setContentDescription("移除 " + file.name);
            remove.setOnClickListener(view -> {
                if (reply != null) return;
                selectedFiles.remove(file);
                updateAttachmentBar();
            });
            chip.addView(remove, new LinearLayout.LayoutParams(dp(40), dp(40)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
            lp.rightMargin = dp(6);
            items.addView(chip, lp);
        }
    }

    private void startSpeechInput() {
        Intent recognition = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "说出你的问题");
        try {
            startActivityForResult(recognition, SPEECH_INPUT);
        } catch (ActivityNotFoundException e) {
            notice("未找到系统语音识别服务，可以使用键盘里的语音输入。");
        }
    }

    private void shareText(String content) {
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, content);
        startActivity(Intent.createChooser(send, "分享"));
    }

    private void speakReply(String text) {
        if (speechReady && speech.isSpeaking()) { speech.stop(); return; }
        pendingSpeech = text;
        if (speech == null) {
            speech = new TextToSpeech(this, status -> getWindow().getDecorView().post(() -> {
                if (isDestroyed() || speech == null) return;
                if (status != TextToSpeech.SUCCESS) {
                    notice("未找到可用的系统朗读服务。");
                    speech.shutdown();
                    speech = null;
                    pendingSpeech = null;
                    return;
                }
                speechReady = true;
                int language = speech.setLanguage(Locale.getDefault());
                if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
                    pendingSpeech = null;
                    speechReady = false;
                    speech.shutdown();
                    speech = null;
                    notice("系统朗读服务缺少当前语言的语音。");
                    return;
                }
                speakPendingText();
            }));
        } else if (speechReady) speakPendingText();
    }

    private void speakPendingText() {
        if (pendingSpeech == null) return;
        String text = pendingSpeech;
        pendingSpeech = null;
        speech.stop();
        int size = Math.min(3000, TextToSpeech.getMaxSpeechInputLength());
        for (int from = 0; from < text.length();) {
            int end = Math.min(from + size, text.length());
            if (end < text.length()) {
                int paragraph = text.lastIndexOf('\n', end - 1);
                if (paragraph > from + size / 2) end = paragraph + 1;
                else if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
            }
            if (speech.speak(text.substring(from, end), TextToSpeech.QUEUE_ADD, null, "reply-" + from)
                    == TextToSpeech.ERROR) {
                notice("系统朗读服务无法朗读这条回复。");
                break;
            }
            from = end;
        }
    }

    private void connectAccount() {
        if (importing || reply != null || pickingFile) {
            notice("请等待当前操作结束。");
            return;
        }
        if (apiKey != null && !reauthNeeded) {
            new AlertDialog.Builder(this).setTitle("重新自动连接账号？")
                    .setMessage("官网会为你登录的账号创建一枚新的 API 密钥。新密钥保存成功后，聊天费用将从该账号扣除。取消会保留当前连接。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("继续自动连接", (dialog, which) -> openAccountSetup())
                    .show();
            return;
        }
        openAccountSetup();
    }

    private void openAccountSetup() {
        setupError = "";
        keyPageOpenedAt = System.currentTimeMillis();
        if (!openPlatform(PlatformActivity.MODE_SETUP)) keyPageOpenedAt = 0;
    }

    private void openTopUp() {
        if (openPlatform(PlatformActivity.MODE_TOP_UP)) {
            afterCheckout = true;
            checkedAfterCheckout = false;
            local.setCheckoutPending(true);
        }
    }

    private boolean openPlatform(String mode) {
        try {
            Intent browser = new Intent(this, PlatformActivity.class).putExtra(PlatformActivity.EXTRA_MODE, mode);
            if (PlatformActivity.MODE_SETUP.equals(mode)) startActivityForResult(browser, PLATFORM_SETUP);
            else if (PlatformActivity.MODE_TOP_UP.equals(mode)) startActivityForResult(browser, PLATFORM_TOP_UP);
            else startActivity(browser);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            notice("无法打开应用内的 DeepSeek 页面。");
            return false;
        }
    }

    private ClipData clipboard() {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        try {
            ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
            return clip != null && clip.getItemCount() == 1 ? clip : null;
        } catch (SecurityException e) {
            return null;
        }
    }

    private String clipboardKey(long openedAt) {
        ClipData clip = clipboard();
        return clip == null ? null : KeyText.fromClipboard(clip.getItemAt(0).getText(),
                clip.getDescription().getTimestamp(), openedAt, System.currentTimeMillis());
    }

    private void importFromClipboard() {
        if (importing) return;
        String key = clipboardKey(0);
        if (key == null) {
            showKeyDialog("请在官网复制密钥，或在下方粘贴已有密钥。", "导入 API 密钥");
        } else {
            validateAndSave(key);
        }
    }

    private void showKeyDialog(String note, String title) {
        if (importing || reply != null) return;
        LinearLayout form = column();
        form.setPadding(dp(24), dp(3), dp(24), 0);
        if (!note.isEmpty()) form.addView(label(note, 14, MUTE, false));
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        field.setSaveEnabled(false);
        field.setHint("sk-…");
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(1024)});
        addWithTop(form, field, 9);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title).setView(form).setNegativeButton("取消", null)
                .setNeutralButton("从剪贴板粘贴", null).setPositiveButton("验证并保存", null).create();
        if (dialog.getWindow() != null) dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
            ClipData clip = clipboard();
            String key = clip == null ? null : KeyText.parse(clip.getItemAt(0).getText());
            if (key == null) field.setError("剪贴板中没有可识别的密钥");
            else {
                field.setText(key);
                field.setSelection(field.length());
            }
        });
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String key = KeyText.parse(field.getText());
            if (key == null) {
                field.setError("请粘贴完整的 DeepSeek API 密钥");
                return;
            }
            field.setText("");
            dialog.dismiss();
            validateAndSave(key);
        });
    }

    private void validateAndSave(String key) {
        importing = true;
        if (setupPending) showOnboarding();
        else setChatNotice("正在验证密钥…", null);
        workers.execute(() -> {
            try {
                DeepSeekApi.Balance verified = DeepSeekApi.balance(key);
                runOnUiThread(() -> finishImport(key, verified));
            } catch (IOException error) {
                if (statusCode(error) == 402) {
                    runOnUiThread(() -> saveConnectedKey(key, null, DeepSeekApi.BalanceLevel.EMPTY));
                } else runOnUiThread(() -> showImportError(error.getMessage()));
            }
        });
    }

    private void finishImport(String key, DeepSeekApi.Balance verified) {
        saveConnectedKey(key, verified, verified == null ? DeepSeekApi.BalanceLevel.UNKNOWN : verified.level);
    }

    private void saveConnectedKey(String key, DeepSeekApi.Balance verified, DeepSeekApi.BalanceLevel level) {
        if (isFinishing() || isDestroyed()) return;
        try {
            if (brokenKey) local.resetBrokenKey();
            local.saveKey(key, setupPending);
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            showImportError("无法在这台手机上保存连接，请重试。");
            return;
        }
        PlatformActivity.keySaved(this);
        importing = false;
        brokenKey = false;
        reauthNeeded = false;
        setupError = "";
        if (!key.equals(apiKey)) {
            for (List<PickedFile> files : draftFiles.values()) {
                for (PickedFile file : files) file.uploadedId = null;
            }
        }
        apiKey = key;
        balance = verified;
        balanceLevel = level;
        balanceLoading = false;
        afterCheckout = false;
        boolean needsCheck = verified == null && level == DeepSeekApi.BalanceLevel.UNKNOWN;
        lastBalanceCheck = needsCheck ? 0 : SystemClock.elapsedRealtime();
        ++balanceRequest;
        saveConversation();
        render();
        if (needsCheck) refreshBalance();
    }

    private void showImportError(String error) {
        if (isDestroyed() || isFinishing()) return;
        importing = false;
        if (setupPending) {
            setupError = error + " 可以再登录一次，或在右上角的连接选项中粘贴已有密钥。";
            showOnboarding();
        } else setChatNotice(error, null);
    }

    private static int statusCode(IOException error) {
        return error instanceof DeepSeekApi.ApiException ? ((DeepSeekApi.ApiException) error).statusCode : 0;
    }

    private void recheckCheckout() {
        checkedAfterCheckout = true;
        refreshBalance();
    }

    private void refreshBalance() {
        refreshBalance(false);
    }

    private void refreshBalance(boolean announce) {
        String key = apiKey;
        if (key == null || guidePausedAt != 0) return;
        int sequence = ++balanceRequest;
        lastBalanceCheck = SystemClock.elapsedRealtime();
        balanceLoading = true;
        if (setupPending) showOnboarding();
        else updateBalanceUi();
        workers.execute(() -> {
            try {
                DeepSeekApi.Balance result = DeepSeekApi.balance(key);
                runOnUiThread(() -> applyBalance(sequence, result, 0, announce));
            } catch (IOException e) {
                runOnUiThread(() -> applyBalance(sequence, null, statusCode(e), announce));
            }
        });
    }

    private void applyBalance(int sequence, DeepSeekApi.Balance result, int status, boolean announce) {
        if (isDestroyed() || isFinishing() || sequence != balanceRequest) return;
        balanceLoading = false;
        balance = result;
        balanceLevel = result == null ? (status == 402 ? DeepSeekApi.BalanceLevel.EMPTY
                : DeepSeekApi.BalanceLevel.UNKNOWN) : result.level;
        if (status == 401) reauthNeeded = true;
        else if (result != null || status == 402) reauthNeeded = false;
        if (balanceLevel == DeepSeekApi.BalanceLevel.FUNDED || balanceLevel == DeepSeekApi.BalanceLevel.LOW) {
            if (afterCheckout) {
                if (!setupPending) notice("当前余额 " + result.display + "，现在可以聊天。");
                afterCheckout = false;
                checkedAfterCheckout = false;
                local.setCheckoutPending(false);
            }
        }
        if (setupPending) showOnboarding();
        else {
            updateBalanceUi();
            if (announce && balanceLevel == DeepSeekApi.BalanceLevel.FUNDED) notice("当前余额 " + result.display);
        }
    }

    private void updateBalanceUi() {
        if (balanceSummary == null) return;
        String text;
        Runnable action;
        int color = ERROR;
        if (reauthNeeded) {
            text = "账号连接已失效 · 重新登录";
            action = this::connectAccount;
        } else if (lastBalanceCheck == 0 || (balanceLoading
                && (afterCheckout || balanceLevel == DeepSeekApi.BalanceLevel.UNKNOWN))) {
            text = "正在查询余额…";
            color = MUTE;
            action = null;
        } else if (balanceLevel == DeepSeekApi.BalanceLevel.EMPTY) {
            if (afterCheckout && !checkedAfterCheckout) {
                text = "余额仍不足 · 再查一次";
                action = this::recheckCheckout;
            } else {
                text = afterCheckout ? "余额仍不足 · 查看充值页" : "可用余额不足 · 去充值";
                action = this::openTopUp;
            }
        } else if (balanceLevel == DeepSeekApi.BalanceLevel.UNKNOWN) {
            text = "余额查询失败 · 重试";
            action = this::refreshBalance;
        } else {
            boolean low = balanceLevel == DeepSeekApi.BalanceLevel.LOW;
            text = (low ? "余额仅剩 " : "余额 ") + balance.display + " · 充值";
            color = low ? ERROR : MUTE;
            action = this::openTopUp;
        }
        balanceSummary.setTextColor(color);
        chatHeading.setAccessibilityLiveRegion(color == ERROR
                ? View.ACCESSIBILITY_LIVE_REGION_POLITE : View.ACCESSIBILITY_LIVE_REGION_NONE);
        if (!text.contentEquals(balanceSummary.getText())) balanceSummary.setText(text);
        chatHeading.setOnClickListener(action == null ? null : view -> action.run());
        chatHeading.setClickable(action != null);
        chatHeading.setFocusable(action != null);
    }

    private void setSending(boolean sending) {
        sendButton.setImageResource(sending ? R.drawable.ic_stop : R.drawable.ic_send);
        sendButton.setContentDescription(sending ? "停止" : "发送");
    }

    private void sendMessage() {
        if (importing || pickingFile) {
            notice("请等待当前操作完成。");
            return;
        }
        String prompt = composer.getText().toString().trim();
        if (prompt.isEmpty() && selectedFiles.isEmpty()) {
            notice("请先输入消息。");
            return;
        }
        startReply(prompt.isEmpty() ? "请概括这些附件的内容。" : prompt, null);
    }

    private void regenerate(DeepSeekApi.Message answer) {
        if (reply != null || importing || pickingFile || messages.size() < 2
                || messages.get(messages.size() - 1) != answer) return;
        DeepSeekApi.Message prompt = messages.get(messages.size() - 2);
        startReply(prompt.content == null ? "请概括这些附件的内容。" : prompt.content, prompt);
    }

    private void startReply(String prompt, DeepSeekApi.Message regeneratePrompt) {
        if (reauthNeeded) {
            connectAccount();
            return;
        }
        if (balanceLevel == DeepSeekApi.BalanceLevel.EMPTY) {
            notice("余额不足。充值后，写好的问题还在这里。");
            if (afterCheckout && !checkedAfterCheckout) recheckCheckout(); else openTopUp();
            return;
        }
        setChatNotice("", null);
        emptyChat.setVisibility(View.GONE);
        if (incompleteReply != null) {
            messageList.removeView(incompleteReply.promptView);
            messageList.removeView((View) incompleteReply.view.getParent());
            incompleteReply = null;
        }
        boolean regenerating = regeneratePrompt != null;
        List<PickedFile> files = regenerating ? Collections.emptyList() : new ArrayList<>(selectedFiles);
        TextView promptView = null;
        if (regenerating) {
            messageList.removeViewAt(messageList.getChildCount() - 1);
        } else {
            promptView = addMessage(messageList, "user", prompt);
            StringBuilder prepared = new StringBuilder(prompt);
            for (PickedFile file : files) prepared.append("\n▧ ").append(file.name);
            promptView.setText(prepared.toString());
        }
        String initialStatus = files.isEmpty() ? (thinking ? "正在思考…" : "正在生成…") : "正在上传附件…";
        TextView answerView = addMessage(messageList, "assistant", initialStatus);
        PendingReply request = new PendingReply(prompt, promptView, answerView, regenerating, initialStatus);
        reply = request;
        if (!regenerating) composer.setText("");
        hideKeyboard();
        composer.setEnabled(false);
        setSending(true);
        saveConversation();
        scrollToBottom();

        int end = regenerating ? messages.size() - 2 : messages.size();
        List<DeepSeekApi.Message> input = new ArrayList<>(messages.subList(Math.max(0, end - CONTEXT_MESSAGES), end));
        String key = apiKey;
        boolean enableThinking = thinking;
        boolean enableSearch = webSearch;
        answerView.postDelayed(() -> updateStreamingText(request), 90);
        workers.execute(() -> {
            try {
                List<DeepSeekApi.Attachment> attachments = uploadFiles(key, files, request);
                DeepSeekApi.Message user = regenerating ? regeneratePrompt
                        : new DeepSeekApi.Message("user", prompt, attachments, null, null);
                input.add(user);
                boundDocuments(input);
                request.status = enableThinking ? "正在思考…" : "正在生成…";
                DeepSeekApi.Message answer = DeepSeekApi.chatMessage(key, input, enableThinking, enableSearch,
                        new DeepSeekApi.ChunkListener() {
                            @Override public void onChunk(String text) { request.latest = text; }
                            @Override public void onReasoning(String text) {
                                request.status = "正在思考…";
                            }
                            @Override public void onToolActivity(String tool) {
                                request.status = "deepseek_read_url".equals(tool) ? "正在阅读链接…" : "正在联网搜索…";
                            }
                        }, request.cancellation);
                runOnUiThread(() -> finishReply(request, user, answer, null));
            } catch (IOException e) {
                runOnUiThread(() -> finishReply(request, null, null, e));
            }
        });
    }

    private List<DeepSeekApi.Attachment> uploadFiles(String key, List<PickedFile> files, PendingReply request)
            throws IOException {
        List<DeepSeekApi.Attachment> result = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            if (request.cancellation.isCancelled()) throw new IOException("已停止上传。");
            PickedFile file = files.get(i);
            request.status = "正在上传附件 " + (i + 1) + "/" + files.size() + "…";
            if (file.uploadedId == null) {
                try (InputStream stream = getContentResolver().openInputStream(file.uri)) {
                    if (stream == null) throw new IOException("无法打开附件，请重新选择。");
                    file.uploadedId = DeepSeekFiles.upload(key, stream, file.name, file.mime, file.length,
                            request.cancellation);
                } catch (SecurityException e) {
                    throw new IOException("没有权限读取附件，请重新选择。", e);
                }
            }
            result.add(new DeepSeekApi.Attachment(file.uploadedId, file.name, file.image));
        }
        return result;
    }

    private void boundDocuments(List<DeepSeekApi.Message> input) {
        int documents = 0;
        for (DeepSeekApi.Message item : input) {
            for (DeepSeekApi.Attachment file : item.attachments) if (!file.image) documents++;
        }
        while (documents > 20 && input.size() > 2) {
            for (DeepSeekApi.Attachment file : input.get(0).attachments) if (!file.image) documents--;
            input.subList(0, 2).clear();
        }
    }

    private void updateStreamingText(PendingReply request) {
        if (reply != request || isDestroyed()) return;
        String content = request.latest.isEmpty() ? request.status : request.latest;
        if (!content.contentEquals(request.view.getText())) {
            boolean follow = nearBottom();
            request.view.setText(content);
            if (follow) scrollToBottom();
        }
        request.view.postDelayed(() -> updateStreamingText(request), 90);
    }

    private void finishReply(PendingReply request, DeepSeekApi.Message user, DeepSeekApi.Message answer, IOException error) {
        if (isDestroyed() || reply != request) return;
        reply = null;
        composer.setEnabled(true);
        setSending(false);
        if (answer != null) {
            boolean follow = nearBottom();
            int previousScroll = chatScroll.getScrollY();
            if (request.regenerating) {
                messages.set(messages.size() - 1, answer);
            } else {
                messages.add(user);
                messages.add(answer);
                selectedFiles.clear();
            }
            saveConversation();
            render();
            if (!follow) chatScroll.post(() -> chatScroll.scrollTo(0, previousScroll));
            refreshBalance();
            return;
        }
        if (request.regenerating) {
            render();
        } else {
            incompleteReply = request;
            request.view.setText(request.latest.isEmpty() ? "未收到回复。" : request.latest);
            if (composer.length() == 0) composer.setText(request.prompt);
        }
        int status = statusCode(error);
        if (status == 401 || status == 402) {
            applyBalance(++balanceRequest, null, status, false);
        } else setChatNotice(error == null ? "未收到回复，可以重新发送。" : error.getMessage(), null);
        saveConversation();
    }

    private void stopReply() {
        PendingReply request = reply;
        reply = null;
        composer.setEnabled(true);
        workers.execute(request.cancellation::cancel);
        if (request.regenerating) {
            render();
            setChatNotice("已停止，保留原回复。", null);
            refreshBalance();
            return;
        }
        incompleteReply = request;
        request.view.setText(request.latest.isEmpty() ? "已停止生成。" : request.latest + "\n\n已停止生成");
        if (composer.length() == 0) composer.setText(request.prompt);
        setSending(false);
        saveConversation();
        refreshBalance();
    }

    private void showOfflineConversation() {
        ScrollView scroll = scrollView();
        LinearLayout records = column();
        records.setPadding(dp(14), dp(4), dp(14), dp(8));
        scroll.addView(records);
        if (messages.isEmpty()) records.addView(label("这段对话还没有消息。", 15, MUTE, false));
        else showSavedMessages(records, messages, false);
        new AlertDialog.Builder(this).setTitle(activeTitle()).setView(scroll)
                .setNegativeButton("返回历史", (box, which) -> showHistory())
                .setPositiveButton("完成连接后继续聊", null)
                .setOnCancelListener(box -> showHistory()).show();
        scroll.post(() -> scroll.scrollTo(0, records.getHeight()));
    }

    private void showSavedMessages(LinearLayout list, List<DeepSeekApi.Message> history, boolean allowRegenerate) {
        int first = Math.max(0, history.size() - HISTORY_PAGE_SIZE);
        if (first > 0) addEarlierMessagesButton(list, history, first);
        addSavedMessages(list, history, first, history.size(), allowRegenerate);
    }

    private void addEarlierMessagesButton(LinearLayout list, List<DeepSeekApi.Message> history, int end) {
        TextView earlier = button("查看更早消息", 14, BLUE, TINT, Color.TRANSPARENT, 14);
        list.addView(earlier, 0, new LinearLayout.LayoutParams(MATCH, dp(44)));
        earlier.setOnClickListener(v -> {
            int first = Math.max(0, end - HISTORY_PAGE_SIZE);
            LinearLayout previous = column();
            addSavedMessages(previous, history, first, end, false);
            list.removeView(earlier);
            list.addView(previous, 0, new LinearLayout.LayoutParams(MATCH, WRAP));
            if (first > 0) addEarlierMessagesButton(list, history, first);
        });
    }

    private void addSavedMessages(LinearLayout list, List<DeepSeekApi.Message> history, int first, int end,
                                  boolean allowRegenerate) {
        for (int i = first; i < end; i++) {
            DeepSeekApi.Message message = history.get(i);
            TextView text = addMessage(list, message.role, message.content);
            if ("assistant".equals(message.role)) {
                boolean canRegenerate = allowRegenerate && i == history.size() - 1 && i > 0
                        && "user".equals(history.get(i - 1).role);
                decorateReply(text, message, canRegenerate);
            } else describeAttachments(text, message.attachments);
        }
    }

    private TextView addMessage(LinearLayout list, String role, String content) {
        TextView message = label(content == null ? "" : content, 16, INK, false);
        message.setLineSpacing(dp(3), 1);
        message.setTextIsSelectable(true);
        if ("user".equals(role)) {
            message.setPadding(dp(15), dp(10), dp(15), dp(10));
            message.setBackground(shape(BUBBLE, Color.TRANSPARENT, 19));
            message.setMaxWidth(dp(285));
            LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(WRAP, WRAP);
            layout.gravity = Gravity.END;
            layout.topMargin = dp(10);
            layout.leftMargin = dp(34);
            list.addView(message, layout);
        } else {
            LinearLayout answer = column();
            answer.setPadding(dp(2), dp(7), dp(2), dp(8));
            answer.addView(message);
            addWithTop(list, answer, 11);
        }
        return message;
    }

    private void describeAttachments(TextView message, List<DeepSeekApi.Attachment> files) {
        if (files.isEmpty()) return;
        StringBuilder text = new StringBuilder(message.getText());
        for (DeepSeekApi.Attachment file : files) {
            if (text.length() > 0) text.append('\n');
            text.append(file.image ? "▧ " : "▤ ").append(file.name);
        }
        message.setText(text.toString());
    }

    private void decorateReply(TextView text, DeepSeekApi.Message message, boolean latest) {
        LinearLayout card = (LinearLayout) text.getParent();
        String content = message.content == null ? "" : message.content;
        text.setText(MarkdownText.render(content, this::openSource));
        text.setMovementMethod(LinkMovementMethod.getInstance());

        if (!message.reasoningContent.trim().isEmpty()) {
            TextView reasoning = label(message.reasoningContent, 14, MUTE, false);
            reasoning.setPadding(dp(10), dp(5), dp(6), dp(6));
            reasoning.setTextIsSelectable(true);
            card.addView(reveal("思考过程", reasoning), 0, new LinearLayout.LayoutParams(MATCH, WRAP));
        }
        if (!message.citations.isEmpty()) {
            LinearLayout citations = column();
            int index = 1;
            for (DeepSeekApi.Citation cite : message.citations) {
                Uri address = publicWebAddress(cite.url);
                if (address == null) continue;
                String source = cite.title == null || cite.title.trim().isEmpty() ? address.getHost() : cite.title;
                TextView link = label(index++ + "  " + source, 14, BLUE, false);
                link.setBackground(tapShape(TINT, Color.TRANSPARENT, 10));
                link.setGravity(Gravity.CENTER_VERTICAL);
                link.setPadding(dp(9), dp(7), dp(9), dp(7));
                link.setMinHeight(dp(40));
                link.setMaxLines(2);
                link.setEllipsize(TextUtils.TruncateAt.END);
                link.setOnClickListener(view -> openSource(cite.url));
                addWithTop(citations, link, 4);
            }
            if (index > 1) addWithTop(card, reveal("查看来源（" + (index - 1) + "）", citations), 6);
        }
        LinearLayout actions = row();
        addReplyAction(actions, "复制", () -> copyReply(content));
        addReplyAction(actions, "朗读", () -> speakReply(MarkdownText.render(content, null).toString()));
        addReplyAction(actions, "分享", () -> shareText(content));
        if (latest) addReplyAction(actions, "重新生成", () -> regenerate(message));
        addWithTop(card, actions, 3);
    }

    private LinearLayout reveal(String title, View content) {
        LinearLayout disclosure = column();
        TextView toggle = button(title + " ▾", 13, BLUE, TINT, Color.TRANSPARENT, 10);
        toggle.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        disclosure.addView(toggle, new LinearLayout.LayoutParams(MATCH, dp(40)));
        content.setVisibility(View.GONE);
        disclosure.addView(content, new LinearLayout.LayoutParams(MATCH, WRAP));
        toggle.setOnClickListener(view -> {
            boolean expanding = content.getVisibility() != View.VISIBLE;
            content.setVisibility(expanding ? View.VISIBLE : View.GONE);
            toggle.setText(title + (expanding ? " ▴" : " ▾"));
        });
        return disclosure;
    }

    private void addReplyAction(LinearLayout row, String name, Runnable action) {
        TextView control = button(name, 12, MUTE, PAPER, Color.TRANSPARENT, 11);
        control.setPadding(dp(8), 0, dp(8), 0);
        control.setOnClickListener(view -> action.run());
        row.addView(control, new LinearLayout.LayoutParams(WRAP, dp(44)));
    }

    private void copyReply(String content) {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("AI 回复", content));
            if (Build.VERSION.SDK_INT < 33) notice("已复制");
        }
    }

    private Uri publicWebAddress(String url) {
        if (url == null) return null;
        Uri address = Uri.parse(url);
        return ("https".equalsIgnoreCase(address.getScheme()) || "http".equalsIgnoreCase(address.getScheme()))
                && address.getHost() != null && address.getUserInfo() == null ? address : null;
    }

    private void openSource(String url) {
        Uri address = publicWebAddress(url);
        if (address == null) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, address).addCategory(Intent.CATEGORY_BROWSABLE));
        } catch (ActivityNotFoundException e) {
            notice("未找到可以打开来源的浏览器。");
        }
    }

    private void setChatNotice(String text, Runnable action) {
        chatNotice.setText(text);
        chatNotice.setVisibility(text == null || text.isEmpty() ? View.GONE : View.VISIBLE);
        chatNotice.setOnClickListener(action == null ? null : view -> action.run());
        chatNotice.setClickable(action != null);
    }

    private boolean saveConversation() {
        if (conversations == null || currentConversation == null) return false;
        if (composer != null) draft = composer.getText().toString();
        String savedDraft = reply != null && !reply.regenerating && draft.isEmpty() ? reply.prompt : draft;
        try {
            currentConversation = conversations.save(currentConversation, messages, savedDraft);
            if (apiKey != null && chatTitle != null) chatTitle.setText(activeTitle());
            return true;
        } catch (ConversationStore.StorageException e) {
            notice("会话未能保存，请稍后重试。");
            return false;
        }
    }

    private void hideKeyboard() {
        InputMethodManager keyboard = getSystemService(InputMethodManager.class);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(composer.getWindowToken(), 0);
        page.requestFocus();
    }

    private boolean nearBottom() {
        return messageList.getHeight() - chatScroll.getScrollY() - chatScroll.getHeight() < dp(90);
    }

    private void scrollToBottom() {
        chatScroll.post(() -> chatScroll.scrollTo(0, messageList.getBottom()));
    }

    private ScrollView scrollView() {
        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        return scroll;
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    private LinearLayout row() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private TextView label(String text, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    private TextView button(String title, int size, int textColor, int fill, int stroke, int radius) {
        TextView view = label(title, size, textColor, true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(15), 0, dp(15), 0);
        view.setBackground(tapShape(fill, stroke, radius));
        view.setFocusable(true);
        view.setClickable(true);
        view.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host,
                    android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName("android.widget.Button");
            }
        });
        return view;
    }

    private GradientDrawable shape(int fill, int stroke, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radius));
        if (stroke != Color.TRANSPARENT) drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private RippleDrawable tapShape(int fill, int stroke, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x214668ea), shape(fill, stroke, radius), null);
    }

    private void addWithTop(LinearLayout parent, View child, int top) {
        addWithTop(parent, child, top, WRAP);
    }

    private void addWithTop(LinearLayout parent, View child, int top, int height) {
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(MATCH, height);
        layout.topMargin = dp(top);
        parent.addView(child, layout);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void notice(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private static final class PendingReply {
        final DeepSeekApi.Cancellation cancellation = new DeepSeekApi.Cancellation();
        final String prompt;
        final TextView promptView;
        final TextView view;
        final boolean regenerating;
        volatile String latest = "";
        volatile String status;

        PendingReply(String prompt, TextView promptView, TextView view, boolean regenerating, String status) {
            this.prompt = prompt;
            this.promptView = promptView;
            this.view = view;
            this.regenerating = regenerating;
            this.status = status;
        }
    }

    private static final class PickedFile {
        final Uri uri;
        final String name;
        final String mime;
        final long length;
        final boolean image;
        volatile String uploadedId;

        PickedFile(Uri uri, String name, String mime, long length, boolean image) {
            this.uri = uri;
            this.name = name;
            this.mime = mime;
            this.length = length;
            this.image = image;
        }
    }
}
