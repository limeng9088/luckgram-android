package org.telegram.luckgram.imagetranslate;

import android.view.View;

import org.telegram.luckgram.translateout.TranslateOutLanguageSheet;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

/**
 * Luckgram Features → Translate images: the image-only language, or "same as message translation"
 * (specs#108 需求 2). Same rows as iOS.
 */
public class ImageTranslateSettingsActivity extends UniversalFragment {

    private static final int ID_LANGUAGE = 1;

    @Override
    protected CharSequence getTitle() {
        return ImageTranslateSettings.translateImageText();
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        String value = ImageTranslateSettings.imageDefault().isEmpty()
                ? ImageTranslateSettings.followReadingText()
                : ImageTranslateSettings.languageName(ImageTranslateSettings.imageDefault());
        items.add(UItem.asButton(ID_LANGUAGE, ImageTranslateSettings.settingsLanguageText(), value));
        items.add(UItem.asShadow(ImageTranslateSettings.settingsInfoText()));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id != ID_LANGUAGE || getParentActivity() == null) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
        builder.setTitle(ImageTranslateSettings.settingsLanguageText());
        builder.setItems(new CharSequence[]{ImageTranslateSettings.followReadingText(), org.telegram.luckgram.translateout.TranslateOutStrings.pickerTitle()}, (d, which) -> {
            if (which == 0) {
                ImageTranslateSettings.followReadingLanguage();
                update();
            } else {
                new TranslateOutLanguageSheet(getParentActivity(), getCurrentAccount(), ImageTranslateLogic.base(ImageTranslateSettings.targetLanguage(getCurrentAccount(), 0)), getResourceProvider(), code -> {
                    ImageTranslateSettings.setLanguage(0, code, false);
                    update();
                }).show();
            }
        });
        showDialog(builder.create());
    }

    private void update() {
        if (listView != null) listView.adapter.update(true);
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
