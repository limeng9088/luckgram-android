/*
 * Luckgram: UI strings for quick replies, in Chinese and English.
 */
package org.telegram.luckgram.quickreplies;

import org.telegram.luckgram.LuckgramStrings;

import java.util.ArrayList;
import java.util.Arrays;

public final class QuickRepliesStrings {

    private QuickRepliesStrings() {
    }

    /** Same rule as every other Luckgram string (LuckgramStrings, feature 00). */
    static boolean isChinese() {
        return LuckgramStrings.isChinese();
    }

    private static String s(String zh, String en) {
        return isChinese() ? zh : en;
    }

    static ArrayList<String> presets() {
        if (isChinese()) {
            return new ArrayList<>(Arrays.asList("在路上了！", "10 分钟后给你回电话可以吗？", "谢谢，收到。", "抱歉，要晚 5 分钟！"));
        }
        return new ArrayList<>(Arrays.asList("On my way!", "Can I call you back in 10 minutes?", "Thanks, got it.", "Sorry, I'll be 5 minutes late!"));
    }

    public static String title() {
        return s("快捷回复", "Quick replies");
    }

    public static String manage() {
        return s("管理", "Manage");
    }

    public static String hint() {
        return s("点按填入输入框，长按直接发送", "Tap to insert, long press to send");
    }

    public static String empty() {
        return s("还没有快捷回复", "No quick replies yet");
    }

    public static String add() {
        return s("添加快捷回复", "Add quick reply");
    }

    public static String edit() {
        return s("编辑快捷回复", "Edit quick reply");
    }

    public static String placeholder() {
        return s("输入回复内容", "Reply text");
    }

    public static String save() {
        return s("保存", "Save");
    }

    public static String cancel() {
        return s("取消", "Cancel");
    }

    public static String delete() {
        return s("删除", "Delete");
    }

    public static String deleteConfirm() {
        return s("删除这条快捷回复？", "Delete this quick reply?");
    }

    public static String limitReached() {
        return s("最多只能保存 " + QuickRepliesStore.MAX_COUNT + " 条快捷回复", "You can save up to " + QuickRepliesStore.MAX_COUNT + " quick replies");
    }

    public static String reorder() {
        return s("拖动排序", "Drag to reorder");
    }

    public static String manageInfo() {
        return s("模板只保存在本机当前账号，不会上传。拖动右侧手柄排序，点按编辑。每条最多 " + QuickRepliesStore.MAX_LENGTH + " 字，最多 " + QuickRepliesStore.MAX_COUNT + " 条。",
            "Templates are stored only on this device, for the current account, and are never uploaded. Drag the handle to reorder, tap to edit. Up to " + QuickRepliesStore.MAX_LENGTH + " characters each, " + QuickRepliesStore.MAX_COUNT + " in total.");
    }
}
