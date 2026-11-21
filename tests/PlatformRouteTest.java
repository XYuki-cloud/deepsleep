package com.simpledeep.chat;

public final class PlatformRouteTest {
    private static final String BASE = "https://platform.deepseek.com";

    public static void main(String[] args) {
        check(PlatformRoute.HOME, BASE, BASE + "/");
        check(PlatformRoute.SIGN_IN, BASE + "/sign_in?redirect=/api_keys", BASE + "/sign_in/");
        check(PlatformRoute.ACCOUNT_FORM, BASE + "/sign_up", BASE + "/mobile_verification?provider=wechat",
                BASE + "/forgot_password");
        check(PlatformRoute.AUTH_CALLBACK, BASE + "/authorized?nonce=x", BASE + "/dsh/authorize",
                BASE + "/dsh/authorized");
        check(PlatformRoute.RESTRICTED, BASE + "/banned");
        check(PlatformRoute.DASHBOARD, BASE + "/usage", BASE + "/usage/model-application", BASE + "/profile",
                BASE + "/transactions", BASE + "/integrations");
        check(PlatformRoute.KEYS, BASE + "/api_keys", BASE + "/api_keys/?view=all#top",
                "https://PLATFORM.deepseek.com:443/api_keys");
        check(PlatformRoute.TOP_UP, BASE + "/top_up", BASE + "/top_up?from=usage");
        check(PlatformRoute.CHECKOUT, BASE + "/wechat-pay", "https://mclient.alipay.com/checkout");
        check(PlatformRoute.OTHER_PLATFORM, BASE + "/future-page", BASE + "/api_keys/other", BASE + "/api%5fkeys",
                BASE + "/api_keys//", BASE + "/#/sign_in", BASE + "//", BASE + "/?code=x");
        check(PlatformRoute.OTHER_SITE, null, "", "bad URL", "http://platform.deepseek.com/api_keys",
                "https://platform.deepseek.com:444/api_keys", "https://platform.deepseek.com.evil.example/api_keys",
                "https://else.example@platform.deepseek.com/api_keys", "https://else.example/?url=" + BASE + "/api_keys",
                "https://chat.deepseek.com/api_keys", "https://badalipay.com");
        for (PlatformRoute route : PlatformRoute.values()) {
            boolean cashier = route == PlatformRoute.TOP_UP || route == PlatformRoute.CHECKOUT;
            if (route.allowsPaymentNavigation() != cashier) throw new AssertionError("payment source " + route);
            if (route.mayRestoreDestination(true, true)) throw new AssertionError("duplicate redirect for " + route);
            if (route.mayRestoreDestination(false, false) != (route == PlatformRoute.DASHBOARD)) {
                throw new AssertionError("unexpected redirect before login for " + route);
            }
            if (route.mayRestoreDestination(false, true) != (route == PlatformRoute.DASHBOARD || route == PlatformRoute.HOME)) {
                throw new AssertionError("unexpected post-login redirect for " + route);
            }
        }
        String wallet = "com.eg.android.AlipayGphone";
        if (!PlatformRoute.acceptsAlipayTarget("alipays", null)
                || !PlatformRoute.acceptsAlipayTarget("ALIPAY", wallet)
                || PlatformRoute.acceptsAlipayTarget("alipays", "other.package")
                || PlatformRoute.acceptsAlipayTarget("https", null)
                || PlatformRoute.acceptsAlipayTarget(null, wallet)) {
            throw new AssertionError("wallet URI handling");
        }
        System.out.println("PlatformRouteTest: official routes, payment sources, wallet targets and redirect limits passed");
    }

    private static void check(PlatformRoute expected, String... urls) {
        for (String url : urls) {
            if (PlatformRoute.from(url) != expected) throw new AssertionError(expected + " for " + url);
        }
    }
}
