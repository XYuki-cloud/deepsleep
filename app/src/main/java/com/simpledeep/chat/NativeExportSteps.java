package com.simpledeep.chat;

/** Fixed, public controls in the original DeepSeek app's export flow. */
final class NativeExportSteps {
    enum Control {
        SIDEBAR(true, "Open sidebar", "打开侧边栏", "開啟側邊欄"),
        CLOSE_SIDEBAR(true, "Close sidebar", "关闭侧边栏", "關閉側邊欄"),
        BACK(true, "Back", "返回"),
        SETTINGS_BUTTON(true, "Settings", "设置", "設定", "My Profile", "个人信息", "個人資訊"),
        SETTINGS_TITLE(false, "Settings", "设置", "設定"),
        ACCOUNT(false, "Account settings", "账号管理", "帳號管理"),
        DATA(false, "Data controls", "数据管理", "資料管理"),
        IMPROVE(false, "Improve the model for everyone", "数据用于优化体验", "資料用於優化體驗"),
        DELETE_ALL(false, "Delete all chats", "删除所有历史对话", "刪除所有歷史對話"),
        EXPORT_PAGE(false, "Export data", "导出所有历史对话", "匯出所有歷史對話"),
        EXPORT(false, "Export", "导出", "匯出"),
        EXPORT_NOTE(false, "This data includes your account information and all chat history.",
                "导出内容中将包含你的账号信息和所有历史对话。", "導出內容將包含你的帳號資訊與所有歷史對話。"),
        EXPORT_DATE(false, "Exported on ", "历史对话于", "歷史對話於"),
        REEXPORT(false, "Re-export", "重新导出", "重新匯出"),
        GENERATING(false, "Generating", "生成中", "產生中"),
        FAILED(false, "Export failed. Please retry later.", "导出失败，请稍候重试。", "匯出失敗，請稍後重試。",
                "Exporting too frequently. Please try again later.", "导出过于频繁，请稍后重试。", "匯出過於頻繁，請稍後重試。"),
        COPY(false, "Copy download link", "复制下载链接", "複製下載連結");

        final boolean navigation;
        final String[] labels;

        Control(boolean navigation, String... labels) {
            this.navigation = navigation;
            this.labels = labels;
        }

        boolean matches(CharSequence value) {
            if (value == null || value.length() > 768) return false;
            int start = 0, end = value.length();
            while (start < end && space(value.charAt(start))) start++;
            while (end > start && space(value.charAt(end - 1))) end--;
            for (String known : labels) {
                int extra = end - start - known.length();
                if (extra < 0 || !beginsAt(value, start, known)) continue;
                if (extra == 0 || this == EXPORT_NOTE || this == EXPORT_DATE) return true;
                if (this == GENERATING && (extra == 1 && value.charAt(end - 1) == '…'
                        || extra == 3 && beginsAt(value, end - 3, "..."))) return true;
            }
            return false;
        }
    }

    interface Screen {
        boolean visible(Control control);
        boolean fixedText(Control control);
        boolean clickable(Control control);
        boolean progress();
        long dateFingerprint();
        /** Some drawers expose a visible text label on their account row instead of an icon description. */
        default void inspectDrawerLabels(boolean includeText) {}
    }

    enum Step {
        SIDEBAR(1), SETTINGS(2), DATA(3), EXPORT_PAGE(4), EXPORT(5), REEXPORT(5), WAIT(0), COPY(5), FAILED(0), UNKNOWN(0);

        final int routePosition;
        Step(int routePosition) { this.routePosition = routePosition; }
    }

    /** Compare the proposed navigation action with the last action actually clicked. */
    static boolean isEarlierMenu(Step next, Step previous) {
        return next != null && previous != null && next.routePosition > 0
                && next.routePosition <= Step.EXPORT_PAGE.routePosition && next.routePosition < previous.routePosition;
    }

    static boolean isDrawerSettingsIcon(CharSequence className, CharSequence description) {
        return className != null && ("android.widget.ImageButton".contentEquals(className)
                || "android.widget.ImageView".contentEquals(className))
                && Control.SETTINGS_BUTTON.matches(description);
    }

    static final class Request {
        final boolean refresh;
        Step submission;
        long oldDate;
        boolean copyWithdrawn;

        Request(boolean refresh) { this.refresh = refresh; }
    }

    static Step next(Screen screen, Request request, Step previous) {
        boolean drawer = screen.visible(Control.CLOSE_SIDEBAR);
        if (drawer || previous == Step.SIDEBAR) {
            screen.inspectDrawerLabels(drawer || !screen.visible(Control.SIDEBAR));
            if (screen.clickable(Control.SETTINGS_BUTTON)) return Step.SETTINGS;
            // The open button of the underlying chat can still be exposed behind the drawer.
            if (drawer) return Step.UNKNOWN;
        }
        if (screen.clickable(Control.SIDEBAR)) return Step.SIDEBAR;
        boolean back = screen.visible(Control.BACK);
        if (!back && screen.clickable(Control.SETTINGS_BUTTON)) return Step.SETTINGS;
        boolean enteredForm = previous == Step.EXPORT_PAGE || previous == Step.EXPORT || previous == Step.REEXPORT;
        boolean exportNote = screen.fixedText(Control.EXPORT_NOTE);
        if ((screen.fixedText(Control.EXPORT_PAGE) && (enteredForm || exportNote || screen.clickable(Control.REEXPORT)))
                || (back || enteredForm) && (exportNote || screen.dateFingerprint() != 0 && screen.clickable(Control.REEXPORT))) {
            if (screen.visible(Control.FAILED)) return Step.FAILED;
            if (request.refresh && request.submission == Step.REEXPORT) {
                boolean generating = screen.visible(Control.GENERATING) || screen.progress();
                if (!screen.visible(Control.COPY) && generating) request.copyWithdrawn = true;
                if (generating || !screen.clickable(Control.COPY)) return Step.WAIT;
                long currentDate = screen.dateFingerprint();
                if (request.copyWithdrawn || request.oldDate != 0 && currentDate != 0 && request.oldDate != currentDate) {
                    return Step.COPY;
                }
                return Step.WAIT;
            }
            if (screen.visible(Control.GENERATING)) return Step.WAIT;
            if (screen.clickable(Control.COPY)) {
                if (!request.refresh || request.submission == Step.EXPORT) return Step.COPY;
                return screen.clickable(Control.REEXPORT) ? Step.REEXPORT : Step.UNKNOWN;
            }
            if (request.submission != null) return Step.WAIT;
            if (screen.clickable(Control.EXPORT)) return Step.EXPORT;
            return Step.UNKNOWN;
        }
        if ((screen.fixedText(Control.DATA) || (back || previous == Step.DATA) && screen.visible(Control.IMPROVE))
                && screen.clickable(Control.EXPORT_PAGE)) return Step.EXPORT_PAGE;
        if ((screen.fixedText(Control.SETTINGS_TITLE) || (back || previous == Step.SETTINGS) && screen.visible(Control.ACCOUNT))
                && screen.clickable(Control.DATA)) return Step.DATA;
        if (previous == request.submission && request.submission != null && screen.progress()) return Step.WAIT;
        return Step.UNKNOWN;
    }

    /** Only fingerprint the generated-on value in the official export notice, never the link or other text. */
    static long dateFingerprint(CharSequence notice) {
        if (notice == null || notice.length() > 320) return 0;
        int start = 0;
        while (start < notice.length() && space(notice.charAt(start))) start++;
        String[] separators = {", expires on ", "导出，将于", "匯出，將於"};
        for (int i = 0; i < Control.EXPORT_DATE.labels.length; i++) {
            String prefix = Control.EXPORT_DATE.labels[i];
            if (!beginsAt(notice, start, prefix)) continue;
            int first = start + prefix.length();
            for (int end = first + 1; end - first < 64 && end < notice.length(); end++) {
                if (!beginsAt(notice, end, separators[i])) continue;
                long hash = 17;
                boolean digit = false;
                for (int pos = first; pos < end; pos++) {
                    char c = notice.charAt(pos);
                    hash = 31 * hash + c;
                    digit |= Character.isDigit(c);
                }
                return digit ? hash : 0;
            }
        }
        return 0;
    }

    private static boolean beginsAt(CharSequence text, int offset, String part) {
        if (offset + part.length() > text.length()) return false;
        for (int i = 0; i < part.length(); i++) if (part.charAt(i) != text.charAt(offset + i)) return false;
        return true;
    }

    private static boolean space(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }

    private NativeExportSteps() {}
}
