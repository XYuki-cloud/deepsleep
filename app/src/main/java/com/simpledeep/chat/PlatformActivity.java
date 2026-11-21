package com.simpledeep.chat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
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
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class PlatformActivity extends Activity {
    static final String EXTRA_MODE = "mode";
    static final String MODE_SETUP = "setup";
    static final String MODE_KEYS = "keys";
    static final String MODE_TOP_UP = "top_up";
    static final String RESULT_KEY = "key";
    static final String RESULT_PASTE = "paste";
    private static final String FLOW_PREFS = "official_key_flow";
    private static final String PENDING_KEY = "creation_pending";
    private static final String PLATFORM_HOST = "platform.deepseek.com";
    private static final int SURFACE = Color.WHITE;
    private static final int INK = 0xff202632;
    private static final int BLUE = 0xff365edf;
    private static final long ACTION_TIMEOUT_MS = 18_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable step = this::advanceKeyFlow;
    private final Runnable watchPage = this::checkCurrentPage;
    private WebView web;
    private TextView title;
    private TextView domain;
    private TextView status;
    private TextView automationState;
    private Button automationToggle;
    private TextView action;
    private ProgressBar progress;
    private boolean topUp;
    private boolean guided;
    private boolean recoverCreation;
    private boolean manual;
    private boolean outsidePayment;
    private AlertDialog walletDialog;
    private Uri declinedWalletTarget;
    private Uri cashierEntry;
    private boolean failed;
    private boolean loading;
    private boolean resumed;
    private boolean evaluating;
    private boolean restoredDestination;
    private boolean sawLogin;
    private boolean visitedTopUp;
    private boolean awaitingRetry;
    private boolean retriedKeyPage;
    private boolean slowLoadShown;
    private String pauseReason;
    private boolean emailCloseAttempted;
    private boolean inspectKey;
    private long settledAt;
    private long loadStarted;
    private long evaluationStarted;
    private int navigation;
    private String startedUrl;
    private String lastSettledUrl;
    private String destination;
    private String helper;
    private String stage;
    private long stageStarted;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        String mode = getIntent().getStringExtra(EXTRA_MODE);
        topUp = MODE_TOP_UP.equals(mode);
        guided = MODE_SETUP.equals(mode);
        destination = "https://" + PLATFORM_HOST + (topUp ? "/top_up" : "/api_keys");
        outsidePayment = topUp && state != null && state.getBoolean("outsidePayment");
        restoredDestination = state != null && state.getBoolean("restoredDestination");
        sawLogin = state != null && state.getBoolean("sawLogin");
        visitedTopUp = state != null && state.getBoolean("visitedTopUp");
        retriedKeyPage = state != null && state.getBoolean("retriedKeyPage");
        if (guided) {
            helper = readHelper();
            recoverCreation = creationPending();
            manual = helper == null || (state != null && state.getBoolean("manual"));
            pauseReason = state == null ? null : state.getString("pauseReason");
            emailCloseAttempted = state != null && state.getBoolean("emailCloseAttempted");
            inspectKey = true;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().setStatusBarColor(Build.VERSION.SDK_INT >= 30 ? Color.TRANSPARENT : SURFACE);
        getWindow().setNavigationBarColor(Build.VERSION.SDK_INT >= 30 ? Color.TRANSPARENT : SURFACE);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        if (Build.VERSION.SDK_INT >= 29) getWindow().setNavigationBarContrastEnforced(false);
        buildView();
        if (!outsidePayment) {
            String initial = state == null ? null : state.getString("pausedPage");
            web.loadUrl(guided && pauseReason != null && initial != null
                    && PlatformRoute.from(initial) != PlatformRoute.OTHER_SITE ? initial : destination);
        }
    }

    private void buildView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(SURFACE);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets safe = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
                return WindowInsets.CONSUMED;
            });
        }
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(8), dp(4), dp(8), dp(4));
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, -2));
        Button close = new Button(this, null, android.R.attr.borderlessButtonStyle);
        close.setText(topUp ? "返回查余额" : "返回");
        close.setContentDescription(topUp ? "返回应用并查询余额" : "返回应用");
        close.setTextSize(15);
        close.setTextColor(BLUE);
        close.setGravity(Gravity.CENTER);
        close.setMinWidth(dp(48));
        close.setMinimumWidth(dp(48));
        close.setPadding(dp(10), 0, dp(10), 0);
        close.setFocusable(true);
        close.setOnClickListener(view -> finish());
        toolbar.addView(close, new LinearLayout.LayoutParams(-2, dp(48)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(8), 0, dp(6), 0);
        toolbar.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        title = new TextView(this);
        title.setText(topUp ? "DeepSeek 官方充值" : guided ? "登录 DeepSeek" : "DeepSeek 密钥管理");
        title.setTextColor(INK);
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        titles.addView(title);
        domain = new TextView(this);
        domain.setText(PLATFORM_HOST);
        domain.setTextColor(0xff596477);
        domain.setTextSize(12);
        titles.addView(domain);
        if (topUp) {
            Button browser = new Button(this, null, android.R.attr.borderlessButtonStyle);
            browser.setText("用浏览器打开");
            browser.setTextColor(BLUE);
            browser.setTextSize(14);
            browser.setMinWidth(0);
            browser.setMinimumWidth(0);
            browser.setPadding(dp(6), 0, dp(6), 0);
            browser.setContentDescription("用手机浏览器打开充值页面");
            browser.setOnClickListener(view -> openCheckoutInBrowser());
            toolbar.addView(browser, new LinearLayout.LayoutParams(-2, dp(48)));
        }

        if (guided) {
            LinearLayout banner = new LinearLayout(this);
            banner.setGravity(Gravity.CENTER_VERTICAL);
            banner.setBackgroundColor(0xfff2f5ff);
            banner.setPadding(dp(16), 0, dp(8), 0);
            automationState = new TextView(this);
            automationState.setTextSize(15);
            automationState.setTextColor(INK);
            automationState.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            banner.addView(automationState, new LinearLayout.LayoutParams(0, -2, 1));
            automationToggle = new Button(this, null, android.R.attr.borderlessButtonStyle);
            automationToggle.setTextColor(BLUE);
            automationToggle.setTextSize(15);
            automationToggle.setOnClickListener(v -> {
                if (pauseReason == null && !reviewingKeyFailure()) pauseAutomation("user");
                else continueAutomation();
            });
            banner.addView(automationToggle, new LinearLayout.LayoutParams(-2, dp(48)));
            root.addView(banner);
            updateAutomationBanner();
        }
        status = new TextView(this);
        status.setTextSize(15);
        status.setTextColor(INK);
        status.setBackgroundColor(0xfff2f5ff);
        status.setPadding(dp(16), dp(9), dp(16), dp(9));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        if (guided || topUp) {
            showStatus("正在打开 DeepSeek 官网…");
        } else {
            status.setVisibility(View.GONE);
        }
        root.addView(status);
        action = new Button(this);
        action.setGravity(Gravity.CENTER);
        action.setTextSize(15);
        action.setTextColor(Color.WHITE);
        action.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(BLUE);
        pill.setCornerRadius(dp(14));
        action.setBackground(pill);
        action.setFocusable(true);
        action.setVisibility(View.GONE);
        LinearLayout.LayoutParams actionSize = new LinearLayout.LayoutParams(-1, dp(48));
        actionSize.setMargins(dp(16), dp(6), dp(16), dp(6));
        root.addView(action, actionSize);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        root.addView(progress, new LinearLayout.LayoutParams(-1, dp(3)));

        web = new WebView(this);
        web.setBackgroundColor(Color.WHITE);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        // Let the cashier replace this tab so an asynchronous target=_blank keeps its order URL.
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int value) {
                progress.setProgress(value);
                progress.setVisibility(value < 100 ? View.VISIBLE : View.INVISIBLE);
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri address = request.getUrl();
                PlatformRoute source = PlatformRoute.from(view.getUrl());
                if (topUp && source.allowsPaymentNavigation() && offerAlipay(address, request.hasGesture())) return true;
                if (!request.isForMainFrame()) return false;
                if (trustedSite(address)) {
                    if (topUp && source == PlatformRoute.TOP_UP && isAlipayWeb(address)
                            && "GET".equalsIgnoreCase(request.getMethod())) {
                        cashierEntry = address;
                    }
                    return false;
                }
                showStatus(topUp ? "这个链接无法在应用里打开。请在下方网页选择其他支付方式。"
                        : "这个链接无法在应用里打开。请继续在下方的 DeepSeek 官网页面操作。");
                return true;
            }

            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                loading = true;
                slowLoadShown = false;
                loadStarted = SystemClock.elapsedRealtime();
                startedUrl = url;
                lastSettledUrl = null;
                failed = false;
                navigation++;
                evaluating = false;
                handler.removeCallbacks(step);
                if (guided && creationPending()) {
                    recoverCreation = true;
                    stage = null;
                } else if (stage != null) {
                    stage = "open";
                }
                inspectKey = true;
                if ("open".equals(stage)) stageStarted = 0;
                Uri address = Uri.parse(url);
                declinedWalletTarget = null;
                configureCheckoutWindow(url);
                domain.setText(trustedSite(address) ? address.getHost() : PLATFORM_HOST);
                showAction(null, null);
                if (guided || topUp) showStatus("正在打开 DeepSeek 网页…");
            }

            @Override public void onPageFinished(WebView view, String url) {
                // A SPA can replace /api_keys with /sign_in before its initial document finishes.
                String current = view.getUrl();
                if (current == null || !(url.equals(current) || url.equals(startedUrl))) return;
                loading = false;
                handleSettledPage(current);
            }

            @Override public void doUpdateVisitedHistory(WebView view, String url, boolean reload) {
                if (!loading && url != null && url.equals(view.getUrl()) && !url.equals(lastSettledUrl)) {
                    handleSettledPage(url);
                }
            }

            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) pageFailed();
            }

            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (request.isForMainFrame()) pageFailed();
            }
        });
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (outsidePayment) {
            finish();
            return;
        }
        lastSettledUrl = null;
        handler.post(watchPage);
        stageStarted = 0;
        inspectKey = true;
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("outsidePayment", outsidePayment);
        outState.putBoolean("manual", manual);
        outState.putBoolean("sawLogin", sawLogin);
        outState.putBoolean("visitedTopUp", visitedTopUp);
        outState.putBoolean("restoredDestination", restoredDestination);
        outState.putBoolean("retriedKeyPage", retriedKeyPage);
        outState.putString("pauseReason", pauseReason);
        outState.putBoolean("emailCloseAttempted", emailCloseAttempted);
        if (guided && pauseReason != null && web.getUrl() != null) {
            PlatformRoute route = PlatformRoute.from(web.getUrl());
            if (route != PlatformRoute.OTHER_PLATFORM && route != PlatformRoute.OTHER_SITE
                    && !route.allowsPaymentNavigation() && route != PlatformRoute.AUTH_CALLBACK) {
                outState.putString("pausedPage", "https://" + PLATFORM_HOST + Uri.parse(web.getUrl()).getEncodedPath());
            }
        }
    }

    @Override protected void onPause() {
        resumed = false;
        invalidateWork();
        handler.removeCallbacks(watchPage);
        super.onPause();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(step);
        handler.removeCallbacks(watchPage);
        if (walletDialog != null) walletDialog.dismiss();
        if (web != null) {
            web.stopLoading();
            if (web.getParent() instanceof LinearLayout) ((LinearLayout) web.getParent()).removeView(web);
            web.destroy();
        }
        super.onDestroy();
    }

    private void configureCheckoutWindow(String url) {
        web.getSettings().setJavaScriptCanOpenWindowsAutomatically(
                topUp && PlatformRoute.from(url).allowsPaymentNavigation());
    }

    private void scheduleStep(long delay) {
        handler.removeCallbacks(step);
        if (resumed && pauseReason == null) handler.postDelayed(step, delay);
    }

    private void invalidateWork() {
        navigation++;
        evaluating = false;
        handler.removeCallbacks(step);
    }

    private void pauseAutomation(String reason) {
        invalidateWork();
        pauseReason = reason;
        inspectKey = true;
        stageStarted = 0;
        showAction(null, null);
        showStatus("");
    }

    private void continueAutomation() {
        invalidateWork();
        pauseReason = null;
        manual = helper == null;
        awaitingRetry = false;
        restoredDestination = false;
        inspectKey = true;
        stageStarted = 0;
        lastSettledUrl = null;
        showStatus("正在识别当前官网页面…");
        if (failed) {
            pageFailed();
        } else if (!loading && web.getUrl() != null) {
            handleSettledPage(web.getUrl());
        }
    }

    private void updateAutomationBanner() {
        if (automationState == null) return;
        PlatformRoute route = PlatformRoute.from(web == null ? null : web.getUrl());
        String label;
        if (pauseReason != null) label = "email".equals(pauseReason) ? "已暂停 · 待处理邮箱提示" : "自动连接已暂停";
        else if (failed) label = "等待重试";
        else if (loading) label = "自动连接 · 加载网页";
        else if (reviewingKeyFailure()) label = "等待手动确认";
        else if (route == PlatformRoute.SIGN_IN || route == PlatformRoute.ACCOUNT_FORM || route == PlatformRoute.AUTH_CALLBACK) label = "等待官网登录";
        else if (route == PlatformRoute.KEYS && !inspectKey && "read".equals(stage)) label = "自动连接 · 正在导入密钥";
        else if (route == PlatformRoute.KEYS && !inspectKey && stage != null) label = "自动连接 · 准备专用密钥";
        else label = "自动连接 · 识别网页";
        if (!label.contentEquals(automationState.getText())) automationState.setText(label);
        String toggle = pauseReason != null ? "继续" : reviewingKeyFailure() ? "重新识别" : "暂停";
        if (!toggle.contentEquals(automationToggle.getText())) {
            automationToggle.setText(toggle);
            automationToggle.setContentDescription(toggle + "自动连接");
        }
    }

    private boolean reviewingKeyFailure() {
        return web != null && isKeyPage(web.getUrl()) && (manual || awaitingRetry || recoverCreation);
    }

    private void checkCurrentPage() {
        if (!resumed || isFinishing() || isDestroyed()) return;
        String url = web.getUrl();
        if (!failed && url != null && pauseReason == null) {
            // History API transitions can omit both a matching finish callback and a history notification.
            // Native load progress lets a completed document settle even when its final URL changed.
            if (loading && web.getProgress() == 100) loading = false;
            if (evaluating && !loading
                    && SystemClock.elapsedRealtime() - evaluationStarted > ACTION_TIMEOUT_MS) {
                // The renderer may never call back. Reject any late result before offering recovery.
                navigation++;
                evaluating = false;
                if (isKeyPage(url)) recoverTimedOutStep();
                else pauseAutomation("unknown");
            }
            if (!loading && !url.equals(lastSettledUrl)) {
                handleSettledPage(url);
            } else if (guided && helper != null && !loading && !evaluating
                    && !isKeyPage(url) && canInspectAccountPage(PlatformRoute.from(url))) {
                inspectPage(url);
            } else if (loading && !slowLoadShown && SystemClock.elapsedRealtime() - loadStarted > ACTION_TIMEOUT_MS) {
                slowLoadShown = true;
                PlatformRoute route = PlatformRoute.from(url);
                if (topUp && (route == PlatformRoute.TOP_UP || route == PlatformRoute.CHECKOUT)) {
                    showStatus("支付页面仍在加载。已经付款可以点左上角返回查余额。");
                } else if (route == PlatformRoute.KEYS && guided && !creationPending()) {
                    showStatus("密钥页加载较慢。可以等网页打开，也可以重新打开连接页。");
                    showAction("重新打开连接页", view -> retryKeyPage());
                } else {
                    showStatus("官网仍在加载。可以继续等，或返回应用后重新进入。");
                }
            }
        }
        handler.postDelayed(watchPage, 800);
    }

    private void handleSettledPage(String url) {
        if (failed || isFinishing() || isDestroyed()) return;
        if (!url.equals(lastSettledUrl)) {
            invalidateWork();
            settledAt = SystemClock.elapsedRealtime();
            inspectKey = true;
            stageStarted = 0;
        }
        lastSettledUrl = url;
        PlatformRoute route = PlatformRoute.from(url);
        configureCheckoutWindow(url);
        if (topUp && !route.allowsPaymentNavigation()) cashierEntry = null;
        Uri current = Uri.parse(url);
        if (trustedSite(current)) domain.setText(current.getHost());
        showAction(null, null);
        if (pauseReason != null) {
            showStatus("");
            return;
        }
        // Login text fields, verification codes and checkout stay entirely in the official website.
        if (route == PlatformRoute.SIGN_IN || route == PlatformRoute.ACCOUNT_FORM || route == PlatformRoute.AUTH_CALLBACK) {
            sawLogin = true;
            visitedTopUp = false;
            if (guided || topUp) {
                title.setText("登录 DeepSeek");
                if (route == PlatformRoute.AUTH_CALLBACK) {
                    showStatus("正在由 DeepSeek 确认授权，请按下方官网提示完成登录。");
                } else if (topUp) {
                    showStatus("请在下方登录刚才连接的 DeepSeek 账号，登录后会打开充值页。");
                } else if (route == PlatformRoute.SIGN_IN) {
                    showStatus("在下方填手机号，点「发送验证码」。把短信里的数字填回网页，再点「登录」。如果看到密码框，先点「验证码登录」。");
                } else {
                    showStatus("按下方网页提示完成账号验证。登录后，应用会自动连接这个账号。");
                }
                showAction(null, null);
            }
            return;
        }
        if (route == PlatformRoute.RESTRICTED) {
            title.setText("DeepSeek 账号状态");
            showStatus("请查看下方官网给出的账号提示。");
            return;
        }
        if (!visitedTopUp && route.mayRestoreDestination(restoredDestination, sawLogin)) {
            if (guided && helper != null) {
                // The platform may mount its account prompt after the initial dashboard paint.
                showStatus("正在查看官网是否需要完成账号验证…");
                return;
            }
            showStatus(topUp ? "正在打开官方充值页…" : "正在打开官方密钥页…");
            web.postDelayed(() -> {
                if (resumed && pauseReason == null && !isFinishing() && !isDestroyed() && !failed && !restoredDestination
                        && route == PlatformRoute.from(web.getUrl())) {
                    restoredDestination = true;
                    web.loadUrl(destination);
                }
            }, 350);
            return;
        }
        if (topUp) {
            title.setText("DeepSeek 官方充值");
            if (route == PlatformRoute.TOP_UP) {
                cashierEntry = null;
                visitedTopUp = true;
                showStatus("确认是刚才连接的账号，选好金额和支付方式，点「去支付」。付好后点左上角「返回查余额」。");
            } else if (route == PlatformRoute.CHECKOUT || route == PlatformRoute.OTHER_SITE) {
                showStatus("请按收银台提示完成付款，付好后返回应用查余额。");
            } else if (visitedTopUp) {
                showStatus("已回到 DeepSeek 开放平台。付款完成后可以返回应用查询余额。");
                showAction("返回查余额", view -> finish());
            } else {
                showStatus("当前在 DeepSeek 开放平台。需要充值时可以继续前往充值页。");
                showAction("前往官方充值页", view -> web.loadUrl(destination));
            }
            return;
        }
        if (!guided) return;
        if (route == PlatformRoute.KEYS) {
            if (inspectKey && helper != null && !manual && !awaitingRetry) {
                title.setText("连接 DeepSeek");
                showStatus("正在识别当前密钥页…");
                scheduleStep(150);
            } else if (recoverCreation) {
                title.setText("继续连接账号");
                showStatus("上次连接中断，官网可能已经创建了密钥。重新连接会为这部手机创建一枚新的密钥。");
                showAction("重新连接", view -> confirmNewKey());
            } else if (awaitingRetry) {
                offerKeyPageRetry();
            } else if (manual || stage == null) {
                manualFallback();
            } else {
                title.setText("正在连接账号");
                showKeyProgress();
                showAction(null, null);
                scheduleStep(350);
            }
        } else {
            if ("open".equals(stage)) {
                stageStarted = 0;
            } else if (stage != null) {
                stage = null;
                recoverCreation = creationPending();
                awaitingRetry = !recoverCreation;
            }
            title.setText("连接 DeepSeek");
            if (route == PlatformRoute.OTHER_SITE || route == PlatformRoute.CHECKOUT) {
                showStatus("请先在当前页面完成操作，然后返回 DeepSeek 开放平台。");
            } else {
                showStatus("当前在 DeepSeek 开放平台。继续连接会回到官方密钥页。");
                showAction("继续连接", view -> {
                    if (helper != null && canInspectAccountPage(route)) {
                        inspectPage(web.getUrl(), true);
                    } else web.loadUrl(destination);
                });
            }
        }
    }

    private boolean canInspectAccountPage(PlatformRoute route) {
        return route == PlatformRoute.HOME || route == PlatformRoute.DASHBOARD || route == PlatformRoute.SIGN_IN
                || route == PlatformRoute.ACCOUNT_FORM || route == PlatformRoute.AUTH_CALLBACK
                || route == PlatformRoute.OTHER_PLATFORM;
    }

    private void inspectPage(String url) {
        inspectPage(url, false);
    }

    private void inspectPage(String url, boolean userRequestedKeys) {
        if (!resumed || loading || failed || pauseReason != null || evaluating || url == null) return;
        evaluating = true;
        evaluationStarted = SystemClock.elapsedRealtime();
        int page = navigation;
        web.evaluateJavascript(helper + "('inspect')", json -> {
            if (isFinishing() || isDestroyed() || page != navigation) return;
            evaluating = false;
            if (!resumed || pauseReason != null || !url.equals(web.getUrl())) return;
            String next;
            try {
                next = new JSONObject(json).getString("status");
            } catch (JSONException | NullPointerException e) {
                next = "unrecognized";
            }
            if ("email".equals(next)) {
                handleEmailPrompt(url, userRequestedKeys);
                return;
            }
            PlatformRoute route = PlatformRoute.from(url);
            if (route == PlatformRoute.KEYS) {
                // The WebView's URL can settle before the platform replaces its loading overlay or document.
                if ("unrecognized".equals(next)) {
                    long now = SystemClock.elapsedRealtime();
                    if (stageStarted == 0) stageStarted = now;
                    if (now - stageStarted < ACTION_TIMEOUT_MS) {
                        showStatus("正在等待官网密钥页显示…");
                        scheduleStep(500);
                    } else {
                        manualFallback();
                    }
                    return;
                }
                inspectKey = false;
                stageStarted = 0;
                if ("open".equals(next) && creationPending()) {
                    recoverCreation = true;
                    stage = null;
                    handleSettledPage(url);
                } else if ("open".equals(next) || "prepare".equals(next) || "submit".equals(next) || "read".equals(next)) {
                    // A submitted form can be read again, but an ambiguous pending request may never open a fresh form.
                    if (creationPending() && "prepare".equals(next)) {
                        manualFallback();
                        return;
                    }
                    stage = next;
                    recoverCreation = false;
                    showKeyProgress();
                    scheduleStep(220);
                } else {
                    manualFallback();
                }
            } else if (userRequestedKeys || route.mayRestoreDestination(restoredDestination, sawLogin)) {
                if (!"clear".equals(next)) {
                    pauseAutomation("unknown");
                } else if (userRequestedKeys || SystemClock.elapsedRealtime() - settledAt >= 1400) {
                    restoredDestination = true;
                    web.loadUrl(destination);
                }
            }
        });
    }

    private void handleEmailPrompt(String url, boolean userRequestedKeys) {
        if (!resumed || pauseReason != null || loading || failed || evaluating) return;
        if (emailCloseAttempted || manual || awaitingRetry) {
            pauseAutomation("email");
            return;
        }
        // Reserve the single close attempt before dispatch. A navigation can invalidate its callback.
        emailCloseAttempted = true;
        evaluating = true;
        evaluationStarted = SystemClock.elapsedRealtime();
        int page = navigation;
        showStatus("正在查看邮箱提示能否关闭…");
        web.evaluateJavascript(helper + "('dismissEmail')", json -> {
            if (isFinishing() || isDestroyed() || page != navigation) return;
            String result;
            try {
                result = new JSONObject(json).getString("status");
            } catch (JSONException | NullPointerException e) {
                pauseAutomation("email");
                return;
            }
            if ("email".equals(result) || "gone".equals(result)) emailCloseAttempted = false;
            if (!"dismissed".equals(result) && !"gone".equals(result)) {
                pauseAutomation("email");
                return;
            }
            // Let the closing animation finish, then inspect the live page before any key operation.
            showStatus("正在重新识别当前官网页面…");
            handler.postDelayed(() -> {
                if (page != navigation || isFinishing() || isDestroyed()) return;
                evaluating = false;
                if (!resumed || pauseReason != null || !url.equals(web.getUrl())) return;
                inspectKey = true;
                stageStarted = 0;
                handleSettledPage(url);
                if (!isKeyPage(url)) inspectPage(url, userRequestedKeys);
            }, "dismissed".equals(result) ? 650 : 0);
        });
    }

    private void advanceKeyFlow() {
        if (evaluating || !resumed || loading || failed || pauseReason != null || !isKeyPage(web.getUrl())) return;
        if (inspectKey && helper != null) {
            inspectPage(web.getUrl());
            return;
        }
        if (stage == null) return;
        if (stageStarted == 0) stageStarted = SystemClock.elapsedRealtime();
        if (SystemClock.elapsedRealtime() - stageStarted > ACTION_TIMEOUT_MS) {
            recoverTimedOutStep();
            return;
        }
        if ("submit".equals(stage) && !creationPending()
                && !getSharedPreferences(FLOW_PREFS, MODE_PRIVATE).edit().putBoolean(PENDING_KEY, true).commit()) {
            manualFallback();
            return;
        }
        evaluating = true;
        evaluationStarted = SystemClock.elapsedRealtime();
        int page = navigation;
        web.evaluateJavascript(helper + "('" + stage + "')", json -> {
            if (isDestroyed() || isFinishing() || page != navigation) return;
            evaluating = false;
            if (!resumed || pauseReason != null || !isKeyPage(web.getUrl()) || stage == null) return;
            applyKeyStep(json);
        });
    }

    private void applyKeyStep(String json) {
        try {
            JSONObject result = new JSONObject(json);
            String action = result.getString("status");
            if (action.equals("email")) {
                handleEmailPrompt(web.getUrl(), false);
            } else if (action.equals("wait")) {
                scheduleStep(550);
            } else if (action.equals("opened") && stage.equals("open")) {
                stage = "prepare";
                stageStarted = SystemClock.elapsedRealtime();
                showKeyProgress();
                scheduleStep(350);
            } else if (action.equals("prepared") && stage.equals("prepare")) {
                if (creationPending()) {
                    recoverCreation = true;
                    stage = null;
                    handleSettledPage(web.getUrl());
                    return;
                }
                stage = "submit";
                stageStarted = SystemClock.elapsedRealtime();
                showKeyProgress();
                scheduleStep(550);
            } else if (action.equals("submitted") && stage.equals("submit")) {
                stage = "read";
                stageStarted = SystemClock.elapsedRealtime();
                showKeyProgress();
                scheduleStep(550);
            } else if (action.equals("key") && stage.equals("read")) {
                String key = KeyText.fromClipboard(result.optString("key", null), 0, 0, 0);
                if (key == null) {
                    manualFallback();
                    return;
                }
                stage = null;
                setResult(RESULT_OK, new Intent().putExtra(RESULT_KEY, key));
                finish();
            } else {
                manualFallback();
            }
        } catch (JSONException | NullPointerException e) {
            manualFallback();
        }
    }

    private void manualFallback() {
        boolean submitted = creationPending();
        awaitingRetry = false;
        manual = true;
        stage = null;
        handler.removeCallbacks(step);
        title.setText("复制密钥，完成连接");
        showStatus(submitted ? "如果下方已显示以 sk- 开头的密钥，点旁边的「复制」。若仍然显示创建表单，请先按网页提示完成创建。"
                : "请在下方点「创建 API key」，名称填 deepsleep，创建后点密钥旁的「复制」。");
        showAction("已复制，返回连接", view -> {
            setResult(RESULT_OK, new Intent().putExtra(RESULT_PASTE, true));
            finish();
        });
    }

    private void showKeyProgress() {
        if ("read".equals(stage)) showStatus("已提交创建请求，正在等待官网确认。");
        else if ("submit".equals(stage)) showStatus("正在提交这部手机的密钥名称…");
        else if ("prepare".equals(stage)) showStatus("已打开创建窗口，正在准备连接…");
        else showStatus("已到达官方密钥页，正在等待网页准备就绪…");
    }

    private void offerKeyPageRetry() {
        handler.removeCallbacks(step);
        awaitingRetry = true;
        stage = null;
        title.setText("等待官方密钥页");
        showStatus("网页还没有准备好。可以重新打开密钥页，继续这次连接。");
        showAction("继续自动连接", view -> retryKeyPage());
    }

    private void recoverTimedOutStep() {
        if (!creationPending() && !retriedKeyPage) offerKeyPageRetry();
        else manualFallback();
    }

    private void retryKeyPage() {
        if (!isKeyPage(web.getUrl())) return;
        awaitingRetry = false;
        if (creationPending() || helper == null) {
            manualFallback();
            return;
        }
        retriedKeyPage = true;
        manual = false;
        stage = "open";
        stageStarted = 0;
        showAction(null, null);
        showStatus("正在重新打开官方密钥页…");
        web.loadUrl(destination);
    }

    private boolean creationPending() {
        return getSharedPreferences(FLOW_PREFS, MODE_PRIVATE).getBoolean(PENDING_KEY, false);
    }

    static void keySaved(Context context) {
        context.getSharedPreferences(FLOW_PREFS, MODE_PRIVATE).edit().remove(PENDING_KEY).apply();
    }

    private void confirmNewKey() {
        new AlertDialog.Builder(this).setTitle("创建一枚新的密钥？")
                .setMessage("官网只在创建时显示一次完整密钥。继续后，应用将创建新密钥来连接这个账号。")
                .setNegativeButton("取消", null)
                .setPositiveButton("创建并连接", (dialog, which) -> {
                    if (!getSharedPreferences(FLOW_PREFS, MODE_PRIVATE).edit().remove(PENDING_KEY).commit()) {
                        showStatus("手机暂时无法保存连接记录。请返回后重试。");
                        return;
                    }
                    recoverCreation = false;
                    manual = helper == null;
                    stage = manual ? null : "open";
                    stageStarted = 0;
                    web.loadUrl(destination);
                }).show();
    }

    private void pageFailed() {
        failed = true;
        invalidateWork();
        showStatus("DeepSeek 官网暂时没能打开。检查网络后再试一次。");
        showAction("重新加载", view -> web.loadUrl(destination));
    }

    private void showAction(String label, View.OnClickListener listener) {
        if (pauseReason != null) label = null;
        action.setVisibility(label == null ? View.GONE : View.VISIBLE);
        action.setOnClickListener(listener);
        if (label != null) action.setText(label);
    }

    private void showStatus(String message) {
        updateAutomationBanner();
        if ("email".equals(pauseReason)) message = "官网仍在提示处理邮箱。请在下方按提示操作，处理好后点上方「继续」。";
        else if ("unknown".equals(pauseReason)) message = "请先处理官网当前的提示，再点「继续」。";
        else if (pauseReason != null) message = "现在可以手动操作网页。准备好后点「继续」，从当前页面接着连接。";
        if (!message.contentEquals(status.getText())) status.setText(message);
        status.setVisibility(View.VISIBLE);
    }

    private boolean offerAlipay(Uri address, boolean userGesture) {
        Uri payment = address;
        String namedPackage = null;
        Uri fallback = null;
        if ("intent".equalsIgnoreCase(address.getScheme())) {
            try {
                Intent parsed = Intent.parseUri(address.toString(), Intent.URI_INTENT_SCHEME);
                namedPackage = parsed.getPackage();
                payment = parsed.getData();
                String link = parsed.getStringExtra("browser_fallback_url");
                if (link != null) {
                    Uri candidate = Uri.parse(link);
                    if (isAlipayWeb(candidate)) fallback = candidate;
                }
            } catch (java.net.URISyntaxException e) {
                return false;
            }
        }
        if (payment == null || !PlatformRoute.acceptsAlipayTarget(payment.getScheme(), namedPackage)) {
            return false;
        }
        if (!resumed || outsidePayment || walletDialog != null) return true;
        Uri target = payment.normalizeScheme();
        if (target.equals(declinedWalletTarget) && !userGesture) return true;
        if (fallback != null || PlatformRoute.from(web.getUrl()) == PlatformRoute.TOP_UP) cashierEntry = fallback;
        confirmWallet(target);
        return true;
    }

    private void confirmWallet(Uri target) {
        if (!resumed || outsidePayment || walletDialog != null
                || !PlatformRoute.from(web.getUrl()).allowsPaymentNavigation()) return;
        declinedWalletTarget = target;
        walletDialog = new AlertDialog.Builder(this).setTitle("打开支付宝？")
                .setMessage("前往支付宝核对这笔充值的金额并确认付款。")
                .setNegativeButton("留在网页", null)
                .setPositiveButton("打开支付宝", (dialog, which) -> launchWallet(target))
                .create();
        walletDialog.setOnDismissListener(dialog -> {
            walletDialog = null;
            if (!outsidePayment && !isFinishing() && !isDestroyed() && target.equals(declinedWalletTarget)) {
                showAction("打开支付宝", view -> confirmWallet(target));
            }
        });
        walletDialog.show();
    }

    private void launchWallet(Uri target) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, target).addCategory(Intent.CATEGORY_BROWSABLE)
                    .setPackage(PlatformRoute.ALIPAY_PACKAGE));
            outsidePayment = true;
        } catch (ActivityNotFoundException e) {
            showWalletFailure("当前手机找不到能打开这笔订单的支付宝。");
        } catch (SecurityException e) {
            showWalletFailure("手机系统阻止了这次支付宝跳转。");
        }
    }

    private void showWalletFailure(String reason) {
        String next = cashierEntry != null ? "可以用手机浏览器打开这笔订单。"
                : "可以用浏览器打开 DeepSeek 充值首页。已经付款时请返回查余额。";
        new AlertDialog.Builder(this).setTitle("未能打开支付宝")
                .setMessage(reason + next)
                .setNegativeButton("留在网页", null)
                .setPositiveButton("用浏览器打开", (dialog, which) -> openCheckoutInBrowser())
                .show();
    }

    private void openCheckoutInBrowser() {
        Uri target = cashierEntry != null ? cashierEntry : Uri.parse(destination);
        Intent browse = new Intent(Intent.ACTION_VIEW, target).addCategory(Intent.CATEGORY_BROWSABLE);
        // Resolve against a generic HTTPS address to avoid sending a cashier App Link back to the wallet.
        // Android delivers the actual order URL in the outer intent to the selected browser.
        browse.setSelector(new Intent(Intent.ACTION_VIEW, Uri.parse("https://browser.invalid/"))
                .addCategory(Intent.CATEGORY_BROWSABLE));
        try {
            startActivity(browse);
            outsidePayment = true;
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "手机未能打开浏览器，请继续使用当前网页。", Toast.LENGTH_LONG).show();
        }
    }

    private boolean isAlipayWeb(Uri address) {
        return trustedSite(address) && PlatformRoute.from(address.toString()) == PlatformRoute.CHECKOUT
                && !PLATFORM_HOST.equalsIgnoreCase(address.getHost());
    }

    private boolean trustedSite(Uri address) {
        if (!"https".equals(address.getScheme()) || address.getUserInfo() != null
                || (address.getPort() != -1 && address.getPort() != 443)) return false;
        String host = address.getHost();
        if (host == null) return false;
        return host.equals("deepseek.com") || host.endsWith(".deepseek.com")
                || (topUp && (host.equals("alipay.com") || host.endsWith(".alipay.com")));
    }

    private boolean isKeyPage(String url) {
        return PlatformRoute.from(url) == PlatformRoute.KEYS;
    }

    private String readHelper() {
        try (InputStream in = getResources().openRawResource(R.raw.official_key_flow);
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[2048];
            int count;
            while ((count = in.read(buffer)) != -1) bytes.write(buffer, 0, count);
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return null;
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
