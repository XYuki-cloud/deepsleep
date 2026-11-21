package com.simpledeep.chat;

import java.net.URI;
import java.net.URISyntaxException;

/** Public routes declared by the DeepSeek Platform web frontend. Matches the raw, top-level URL. */
enum PlatformRoute {
    SIGN_IN, ACCOUNT_FORM, AUTH_CALLBACK, RESTRICTED, HOME, DASHBOARD, KEYS, TOP_UP, CHECKOUT,
    OTHER_PLATFORM, OTHER_SITE;

    static final String ALIPAY_PACKAGE = "com.eg.android.AlipayGphone";

    static PlatformRoute from(String url) {
        if (url == null) return OTHER_SITE;
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException malformed) {
            return OTHER_SITE;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null
                || uri.getPort() != -1 && uri.getPort() != 443) return OTHER_SITE;
        String host = uri.getHost();
        if (host == null) return OTHER_SITE;
        if ("alipay.com".equalsIgnoreCase(host) || host.toLowerCase(java.util.Locale.ROOT).endsWith(".alipay.com")) {
            return CHECKOUT;
        }
        if (!"platform.deepseek.com".equalsIgnoreCase(host)) return OTHER_SITE;
        String path = uri.getRawPath();
        if (path.isEmpty()) path = "/";
        if (path.endsWith("//")) return OTHER_PLATFORM;
        if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
        switch (path) {
            case "/": return uri.getRawQuery() == null && uri.getRawFragment() == null ? HOME : OTHER_PLATFORM;
            case "/usage":
            case "/profile":
            case "/usage/model-application":
            case "/transactions":
            case "/integrations": return DASHBOARD;
            case "/api_keys": return KEYS;
            case "/top_up": return TOP_UP;
            case "/wechat-pay": return CHECKOUT;
            case "/sign_in": return SIGN_IN;
            case "/sign_up":
            case "/mobile_verification":
            case "/forgot_password": return ACCOUNT_FORM;
            case "/authorized":
            case "/dsh/authorize":
            case "/dsh/authorized": return AUTH_CALLBACK;
            case "/banned": return RESTRICTED;
            default: return OTHER_PLATFORM;
        }
    }

    boolean mayRestoreDestination(boolean restored, boolean sawLogin) {
        return !restored && (this == DASHBOARD || this == HOME && sawLogin);
    }

    boolean allowsPaymentNavigation() {
        return this == TOP_UP || this == CHECKOUT;
    }

    static boolean acceptsAlipayTarget(String scheme, String namedPackage) {
        return (namedPackage == null || ALIPAY_PACKAGE.equals(namedPackage))
                && ("alipays".equalsIgnoreCase(scheme) || "alipay".equalsIgnoreCase(scheme));
    }
}
