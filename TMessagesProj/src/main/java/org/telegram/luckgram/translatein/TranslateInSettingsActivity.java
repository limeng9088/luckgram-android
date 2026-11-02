/*
 * Luckgram — spec 01 A.3/A.4: settings of received-message translation (secondary page of the
 * translate_in switch in Settings → Luckgram features → Messages & Language).
 * Licensed under GNU GPL v. 2 or later.
 */
package org.telegram.luckgram.translatein;

import android.view.View;

import org.telegram.luckgram.LuckgramStrings;
import org.telegram.luckgram.translateout.TranslateOutController;
import org.telegram.luckgram.translateout.TranslateOutLanguageSheet;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;
import org.telegram.ui.RestrictedLanguagesSelectActivity;

import java.util.ArrayList;

public class TranslateInSettingsActivity extends UniversalFragment {

    private static final int ID_TO_LANG = 1;
    private static final int ID_DO_NOT_TRANSLATE = 2;

    private static String s(String zh, String en) {
        return LuckgramStrings.get(zh, en);
    }

    @Override
    protected CharSequence getTitle() {
        return s("收到消息翻译", "Translate received messages");
    }

    @Override
    public void onResume() {
        super.onResume();
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(false);
        }
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asButton(ID_TO_LANG, s("翻译成", "Translate to"),
            TranslateOutController.languageName(TranslateInLogic.base(LuckgramTranslateIn.targetLanguage()))));
        items.add(UItem.asButton(ID_DO_NOT_TRANSLATE, s("不翻译的语言", "Do not translate"), LuckgramTranslateIn.doNotTranslateSummary()));
        items.add(UItem.asShadow(s(
            "长按消息选「翻译」，或点消息旁的翻译图标，译文显示在原文下方。「不翻译的语言」的消息不显示翻译图标，长按仍可翻译。",
            "Long-press a message and choose Translate, or tap the translate icon next to it; the translation appears below the original. Messages in a \"do not translate\" language get no icon but can still be translated from the menu.")));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_TO_LANG) {
            new TranslateOutLanguageSheet(getContext(), currentAccount, TranslateInLogic.base(LuckgramTranslateIn.targetLanguage()), getResourceProvider(), code -> {
                LuckgramTranslateIn.setTargetLanguage(code);
                if (listView != null) {
                    listView.adapter.update(true);
                }
            }).show();
        } else if (item.id == ID_DO_NOT_TRANSLATE) {
            presentFragment(new RestrictedLanguagesSelectActivity());
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
