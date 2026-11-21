package com.simpledeep.chat;

import java.util.EnumSet;
import static com.simpledeep.chat.NativeExportSteps.Control.*;
import static com.simpledeep.chat.NativeExportSteps.Step;

public final class NativeExportStepsTest {
    private static final class Page implements NativeExportSteps.Screen {
        final EnumSet<NativeExportSteps.Control> text = EnumSet.noneOf(NativeExportSteps.Control.class);
        final EnumSet<NativeExportSteps.Control> buttons = EnumSet.noneOf(NativeExportSteps.Control.class);
        boolean spinner;
        boolean profileRow;
        boolean profileIcon;
        boolean readDrawer;
        long version;
        Page title(NativeExportSteps.Control label) { text.add(label); return this; }
        Page button(NativeExportSteps.Control label) { buttons.add(label); return this; }
        public boolean clickable(NativeExportSteps.Control control) { return buttons.contains(control); }
        public boolean visible(NativeExportSteps.Control control) { return text.contains(control) || buttons.contains(control); }
        public boolean fixedText(NativeExportSteps.Control control) { return text.contains(control); }
        public boolean progress() { return spinner; }
        public long dateFingerprint() { return version; }
        public void inspectDrawerLabels(boolean includeText) {
            readDrawer = true;
            if (profileIcon || (includeText && profileRow)) buttons.add(SETTINGS_BUTTON);
        }
    }

    private static void expect(Step expected, Page page, boolean submitted, Step previous) {
        NativeExportSteps.Request request = new NativeExportSteps.Request(false);
        if (submitted) request.submission = Step.EXPORT;
        expect(expected, page, request, previous);
    }

    private static void expect(Step expected, Page page, NativeExportSteps.Request request, Step previous) {
        Step actual = NativeExportSteps.next(page, request, previous);
        if (actual != expected) throw new AssertionError("expected " + expected + " but got " + actual);
    }

    public static void main(String[] args) {
        Step[] menus = { Step.SIDEBAR, Step.SETTINGS, Step.DATA, Step.EXPORT_PAGE };
        for (int i = 0; i < menus.length; i++) {
            Step next = menus[i];
            for (int j = 0; j < menus.length; j++) {
                if (NativeExportSteps.isEarlierMenu(next, menus[j]) != (i < j)) {
                    throw new AssertionError("unexpected menu order " + menus[j] + " -> " + next);
                }
            }
            if (NativeExportSteps.isEarlierMenu(next, null)
                    || !NativeExportSteps.isEarlierMenu(next, Step.EXPORT)
                    || !NativeExportSteps.isEarlierMenu(next, Step.REEXPORT)) {
                throw new AssertionError("unexpected menu order before start or after submission: " + next);
            }
        }
        for (Step next : new Step[]{ Step.EXPORT, Step.REEXPORT, Step.WAIT, Step.COPY, Step.FAILED, Step.UNKNOWN }) {
            if (NativeExportSteps.isEarlierMenu(next, Step.COPY)) {
                throw new AssertionError("export results must bypass the menu guard: " + next);
            }
        }
        Page chat = new Page().title(EXPORT_PAGE).button(EXPORT).button(COPY);
        expect(Step.UNKNOWN, chat, false, null);
        expect(Step.SIDEBAR, chat.button(SIDEBAR), false, null);
        expect(Step.SETTINGS, new Page().title(EXPORT_PAGE).button(COPY).button(SETTINGS_BUTTON), false, null);
        expect(Step.DATA, new Page().title(SETTINGS_TITLE).button(DATA), false, Step.SETTINGS);
        expect(Step.EXPORT_PAGE, new Page().title(DATA).button(EXPORT_PAGE), false, Step.DATA);
        Page withProfileRow = new Page();
        withProfileRow.profileRow = true;
        expect(Step.UNKNOWN, withProfileRow, false, null);
        if (withProfileRow.readDrawer) throw new AssertionError("inspected profile text outside the drawer");
        expect(Step.SETTINGS, withProfileRow.button(CLOSE_SIDEBAR), false, null);
        Page drawerAfterOpen = new Page();
        drawerAfterOpen.profileRow = true;
        expect(Step.SETTINGS, drawerAfterOpen, false, Step.SIDEBAR);
        Page behindDrawer = new Page().button(SIDEBAR).button(CLOSE_SIDEBAR);
        expect(Step.UNKNOWN, behindDrawer, false, null);
        behindDrawer.profileRow = true;
        expect(Step.SETTINGS, behindDrawer, false, null);
        Page staleOpen = new Page().button(SIDEBAR);
        staleOpen.profileRow = true;
        expect(Step.SIDEBAR, staleOpen, false, Step.SIDEBAR); // No text lookup on the underlying chat.
        staleOpen.profileIcon = true;
        expect(Step.SETTINGS, staleOpen, false, Step.SIDEBAR);
        if (!NativeExportSteps.isDrawerSettingsIcon("android.widget.ImageButton", "设置")
                || !NativeExportSteps.isDrawerSettingsIcon("android.widget.ImageView", "個人資訊")
                || NativeExportSteps.isDrawerSettingsIcon("android.widget.TextView", "设置")
                || NativeExportSteps.isDrawerSettingsIcon("android.view.View", "My Profile")
                || NativeExportSteps.isDrawerSettingsIcon("android.widget.ImageButton", "我的聊天设置")) {
            throw new AssertionError("scrollable drawer must match named icon actions only");
        }
        expect(Step.DATA, new Page().button(BACK).button(ACCOUNT).button(DATA), false, null);
        expect(Step.EXPORT_PAGE, new Page().button(BACK).button(IMPROVE).button(EXPORT_PAGE), false, null);
        expect(Step.UNKNOWN, new Page().button(ACCOUNT).button(DATA), false, null);
        expect(Step.UNKNOWN, new Page().button(BACK).button(DATA).button(DELETE_ALL), false, null);
        expect(Step.UNKNOWN, new Page().title(DATA).button(DELETE_ALL), false, Step.DATA);
        expect(Step.EXPORT, new Page().button(BACK).title(EXPORT_NOTE).button(EXPORT), false, null);
        if (!SIDEBAR.matches("打开侧边栏") || !CLOSE_SIDEBAR.matches("關閉側邊欄")
                || !SETTINGS_BUTTON.matches("　个人信息　") || !SETTINGS_BUTTON.matches("個人資訊")
                || !SETTINGS_BUTTON.matches("My Profile") || !ACCOUNT.matches("账号管理")
                || !DATA.matches("資料管理") || !IMPROVE.matches("数据用于优化体验")
                || !EXPORT_PAGE.matches("导出所有历史对话") || !COPY.matches("複製下載連結")
                || !GENERATING.matches("生成中…") || !GENERATING.matches("產生中...")
                || EXPORT_PAGE.matches("删除所有历史对话") || DATA.matches("如何打开数据管理")
                || EXPORT.matches("重新导出") || SETTINGS_BUTTON.matches("个人信息，注销账号")) {
            throw new AssertionError("public labels must identify a single unambiguous action");
        }
        expect(Step.UNKNOWN, new Page().button(DATA).button(EXPORT_PAGE).button(EXPORT), false, null);
        Page fresh = new Page().title(EXPORT_PAGE).title(EXPORT_NOTE).button(EXPORT);
        expect(Step.EXPORT, fresh, false, null);
        expect(Step.WAIT, fresh, true, Step.EXPORT);
        expect(Step.WAIT, new Page().title(EXPORT_PAGE).title(GENERATING), true, Step.EXPORT);
        expect(Step.WAIT, new Page().title(EXPORT_PAGE), true, Step.EXPORT);
        Page loading = new Page();
        loading.spinner = true;
        expect(Step.WAIT, loading, true, Step.EXPORT);
        expect(Step.UNKNOWN, loading, false, null);
        expect(Step.FAILED, new Page().title(EXPORT_PAGE).title(FAILED), true, Step.EXPORT);
        expect(Step.COPY, new Page().title(EXPORT_PAGE).button(REEXPORT).button(COPY), false, null);
        expect(Step.COPY, new Page().title(EXPORT_PAGE).button(COPY), true, Step.EXPORT);
        expect(Step.UNKNOWN, new Page().title(EXPORT_PAGE).button(REEXPORT), false, null);
        expect(Step.UNKNOWN, new Page().button(COPY).button(EXPORT), false, Step.EXPORT_PAGE);
        expect(Step.UNKNOWN, new Page(), false, null);
        Page ready = new Page().title(EXPORT_PAGE).button(COPY).button(REEXPORT);
        NativeExportSteps.Request refresh = new NativeExportSteps.Request(true);
        expect(Step.REEXPORT, ready, refresh, Step.EXPORT_PAGE);
        expect(Step.UNKNOWN, new Page().button(COPY).button(REEXPORT), refresh, Step.EXPORT_PAGE);
        expect(Step.UNKNOWN, new Page().title(EXPORT_PAGE).button(COPY), refresh, Step.EXPORT_PAGE);
        refresh.submission = Step.REEXPORT;
        expect(Step.WAIT, ready, refresh, Step.REEXPORT); // Old copy is still present.
        expect(Step.WAIT, ready.title(GENERATING), refresh, Step.REEXPORT);
        ready.text.remove(GENERATING);
        expect(Step.WAIT, ready, refresh, Step.REEXPORT); // A transient spinner did not withdraw the old link.
        Page generating = new Page().title(EXPORT_PAGE).title(GENERATING);
        expect(Step.WAIT, generating, refresh, Step.REEXPORT);
        expect(Step.COPY, ready, refresh, Step.REEXPORT);
        refresh.copyWithdrawn = false;
        refresh.oldDate = NativeExportSteps.dateFingerprint("Exported on Nov 20 12:00, expires on Nov 27.");
        ready.version = refresh.oldDate;
        if (refresh.oldDate == 0) throw new AssertionError("export generated-on timestamp not found");
        expect(Step.WAIT, ready, refresh, Step.REEXPORT);
        ready.version = NativeExportSteps.dateFingerprint("Exported on Nov 20 12:01, expires on Nov 27.");
        expect(Step.COPY, ready, refresh, Step.REEXPORT);
        if (NativeExportSteps.dateFingerprint("unrelated 123") != 0
                || NativeExportSteps.dateFingerprint("历史对话于 2026/11/20 导出，将于 2026/11/27 过期。") == 0) {
            throw new AssertionError("non-export content or Chinese export timestamp");
        }
        expect(Step.FAILED, generating.title(FAILED), refresh, Step.REEXPORT);
        expect(Step.UNKNOWN, new Page().button(EXPORT), refresh, Step.REEXPORT);
        System.out.println("NativeExportStepsTest: menu context, ready archive, single submission and unrelated screens passed");
    }
}
