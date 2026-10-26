package org.telegram.ui;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.luckgram.cache.LuckgramCacheStrings;
import org.telegram.luckgram.cache.LuckgramChatKeepMedia;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.CacheByChatsController;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.CheckBoxCell;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.BottomSheetWithRecyclerListView;
import org.telegram.ui.Components.CombinedDrawable;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.StorageDiagramView;
import org.telegram.ui.Storage.CacheModel;

public class DialogCacheBottomSheet extends BottomSheetWithRecyclerListView {

    private final StorageDiagramView circleDiagramView;
    CacheControlActivity.DialogFileEntities entities;
    private final Delegate cacheDelegate;
    private CacheControlActivity.ClearCacheButton button;
    private StorageDiagramView.ClearViewData[] clearViewData = new StorageDiagramView.ClearViewData[8];
    CheckBoxCell[] checkBoxes = new CheckBoxCell[8];

    LinearLayout linearLayout;
    CachedMediaLayout cachedMediaLayout;
    long dialogId;

    private final CacheModel cacheModel;

    @Override
    protected CharSequence getTitle() {
        return getBaseFragment().getMessagesController().getFullName(dialogId);
    }

    @Override
    protected RecyclerListView.SelectionAdapter createAdapter(RecyclerListView listView) {
        return new RecyclerListView.SelectionAdapter() {

            @Override
            public boolean isEnabled(RecyclerView.ViewHolder holder) {
                return false;
            }

            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                View view;
                if (viewType == 0) {
                    view = linearLayout;
                } else if (viewType == 2) {
                    view = cachedMediaLayout;
                    RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    lp.leftMargin = backgroundPaddingLeft;
                    lp.rightMargin = backgroundPaddingLeft;
                    view.setLayoutParams(lp);
                } else {
                    TextInfoPrivacyCell textInfoPrivacyCell = new TextInfoPrivacyCell(parent.getContext());
                    textInfoPrivacyCell.setFixedSize(12);
                    CombinedDrawable combinedDrawable = new CombinedDrawable(
                            new ColorDrawable(Theme.getColor(Theme.key_windowBackgroundGray)),
                            Theme.getThemedDrawableByKey(parent.getContext(), R.drawable.greydivider_bottom, Theme.key_windowBackgroundGrayShadow)
                    );
                    combinedDrawable.setFullsize(true);
                    textInfoPrivacyCell.setBackgroundDrawable(combinedDrawable);
                    view = textInfoPrivacyCell;
                }
                return new RecyclerListView.Holder(view);
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {

            }

            @Override
            public int getItemViewType(int position) {
                return position;
            }

            @Override
            public int getItemCount() {
                return cacheModel.isEmpty() ? 1 : 3;
            }
        };
    }

    @Override
    protected boolean canDismissWithSwipe() {
        return false;
    }

    public DialogCacheBottomSheet(CacheControlActivity baseFragment, CacheControlActivity.DialogFileEntities entities, CacheModel cacheModel, Delegate delegate) {
        super(baseFragment, false, false, !cacheModel.isEmpty(), null);
        this.cacheDelegate = delegate;
        this.entities = entities;
        this.cacheModel = cacheModel;
        dialogId = entities.dialogId;
        allowNestedScroll = false;
        updateTitle();
        setAllowNestedScroll(true);
        topPadding = 0.2f;

        Context context = baseFragment.getContext();
        fixNavigationBar();
        setApplyBottomPadding(false);
        linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(LinearLayout.VERTICAL);
        if (entities != null) {
            circleDiagramView = new StorageDiagramView(getContext(), entities.dialogId) {
                @Override
                protected void onAvatarClick() {
                    delegate.onAvatarClick();
                }
            };
        } else {
            circleDiagramView = new StorageDiagramView(getContext());
        }
        linearLayout.addView(circleDiagramView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 16, 0, 16));

        // Luckgram 08 (cache): per-chat keep-media override (not for Saved Messages, which is always kept)
        if (entities != null && entities.dialogId != CacheControlActivity.UNKNOWN_CHATS_DIALOG_ID && entities.dialogId != baseFragment.getUserConfig().getClientUserId()) {
            HeaderCell keepMediaHeader = new HeaderCell(context);
            keepMediaHeader.setText(LuckgramCacheStrings.chatKeepMedia());
            linearLayout.addView(keepMediaHeader, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            TextSettingsCell keepMediaCell = new TextSettingsCell(context);
            keepMediaCell.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
            keepMediaCell.setTextValueColor(Theme.getColor(Theme.key_dialogTextBlue));
            keepMediaCell.setBackgroundDrawable(Theme.getSelectorDrawable(false));
            CacheByChatsController controller = baseFragment.getMessagesController().getCacheByChatsController();
            keepMediaCell.setTextAndValue(LuckgramCacheStrings.chatKeepMediaRow(), LuckgramChatKeepMedia.valueFor(controller, entities.dialogId), true);
            keepMediaCell.setOnClickListener(v -> LuckgramChatKeepMedia.showPicker(baseFragment, entities.dialogId, entities.totalSize, () ->
                    keepMediaCell.setTextAndValue(LuckgramCacheStrings.chatKeepMediaRow(), LuckgramChatKeepMedia.valueFor(controller, entities.dialogId), true, true)));
            linearLayout.addView(keepMediaCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            TextView keepMediaInfo = new TextView(context);
            keepMediaInfo.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            keepMediaInfo.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText4));
            keepMediaInfo.setText(LuckgramCacheStrings.chatKeepMediaInfo(AndroidUtilities.formatFileSize(entities.totalSize)));
            linearLayout.addView(keepMediaInfo, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 21, 4, 21, 12));
        }
        CheckBoxCell lastCreatedCheckbox = null;
        for (int a = 0; a < 8; a++) {
            long size = 0;
            String name;
            int color;

            if (a == CacheControlActivity.TYPE_PHOTOS) {
                name = LocaleController.getString(R.string.LocalPhotoCache);
                color = Theme.key_statisticChartLine_lightblue;
            } else if (a == CacheControlActivity.TYPE_VIDEOS) {
                name = LocaleController.getString(R.string.LocalVideoCache);
                color = Theme.key_statisticChartLine_blue;
            } else if (a == CacheControlActivity.TYPE_DOCUMENTS) {
                name = LocaleController.getString(R.string.LocalDocumentCache);
                color = Theme.key_statisticChartLine_green;
            } else if (a == CacheControlActivity.TYPE_MUSIC) {
                name = LocaleController.getString(R.string.LocalMusicCache);
                color = Theme.key_statisticChartLine_red;
            } else if (a == CacheControlActivity.TYPE_VOICE) {
                name = LocaleController.getString(R.string.LocalAudioCache);
                color = Theme.key_statisticChartLine_lightgreen;
            } else if (a == CacheControlActivity.TYPE_ANIMATED_STICKERS_CACHE) {
                name = LocaleController.getString(R.string.LocalStickersCache);
                color = Theme.key_statisticChartLine_orange;
            } else if (a == CacheControlActivity.TYPE_STORIES) {
                name = LocaleController.getString(R.string.LocalStoriesCache);
                color = Theme.key_statisticChartLine_indigo;
            } else {
                name = LocaleController.getString(R.string.LocalMiscellaneousCache);
                color = Theme.key_statisticChartLine_purple;
            }
            if (entities != null) {
                CacheControlActivity.FileEntities fileEntities = entities.entitiesByType.get(a);
                if (fileEntities != null) {
                    size = fileEntities.totalSize;
                } else {
                    size = 0;
                }
            }
            if (size > 0) {
                clearViewData[a] = new StorageDiagramView.ClearViewData(circleDiagramView);
                clearViewData[a].size = size;
                clearViewData[a].colorKey = color;
                CheckBoxCell checkBoxCell = new CheckBoxCell(context, 4, 21, null);
                lastCreatedCheckbox = checkBoxCell;
                checkBoxCell.setTag(a);
                checkBoxCell.setBackgroundDrawable(Theme.getSelectorDrawable(false));
                linearLayout.addView(checkBoxCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 50));
                checkBoxCell.setText(name, AndroidUtilities.formatFileSize(size), true, true);
                checkBoxCell.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
                checkBoxCell.setCheckBoxColor(color, Theme.key_windowBackgroundWhiteGrayIcon, Theme.key_checkboxCheck);
                checkBoxCell.setOnClickListener(v -> {
                    int enabledCount = 0;
                    for (int i = 0; i < clearViewData.length; i++) {
                        if (clearViewData[i] != null && clearViewData[i].clear) {
                            enabledCount++;
                        }
                    }
                    CheckBoxCell cell = (CheckBoxCell) v;
                    int num = (Integer) cell.getTag();
//                    if (enabledCount == 1 && clearViewData[num].clear) {
//                        BotWebViewVibrationEffect.APP_ERROR.vibrate();
//                        AndroidUtilities.shakeViewSpring(((CheckBoxCell) v).getCheckBoxView(), -3);
//                        return;
//                    }

                    clearViewData[num].setClear(!clearViewData[num].clear);
                    cell.setChecked(clearViewData[num].clear, true);
                    cacheModel.allFilesSelcetedByType(num, clearViewData[num].clear);
                    cachedMediaLayout.update();
                    long totalSize = circleDiagramView.updateDescription();
                    button.setSize(true, totalSize);
                    circleDiagramView.update(true);
                });
                checkBoxes[a] = checkBoxCell;
            } else {
                clearViewData[a] = null;
                checkBoxes[a] = null;
            }
        }
        if (lastCreatedCheckbox != null) {
            lastCreatedCheckbox.setNeedDivider(false);
        }
        circleDiagramView.setData(cacheModel, clearViewData);
        cachedMediaLayout = new CachedMediaLayout(getContext(), baseFragment) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(contentHeight - ActionBar.getCurrentActionBarHeight() - AndroidUtilities.statusBarHeight, MeasureSpec.EXACTLY));
            }
        };
        cachedMediaLayout.setBottomPadding(AndroidUtilities.dp(80));
        cachedMediaLayout.setCacheModel(cacheModel);
        cachedMediaLayout.setDelegate(new CachedMediaLayout.Delegate() {
            @Override
            public void onItemSelected(CacheControlActivity.DialogFileEntities entities, CacheModel.FileInfo fileInfo, boolean longPress) {
                if (fileInfo != null) {
                    cacheModel.toggleSelect(fileInfo);
                    cachedMediaLayout.updateVisibleRows();
                    syncCheckBoxes();
                    long totalSize = circleDiagramView.updateDescription();
                    button.setSize(true, totalSize);
                    circleDiagramView.update(true);
                }
            }

            @Override
            public void dismiss() {
                DialogCacheBottomSheet.this.dismiss();
            }

            @Override
            public void clear() {

            }

            @Override
            public void clearSelection() {
            }
        });
        if (nestedSizeNotifierLayout != null) {
            nestedSizeNotifierLayout.setChildLayout(cachedMediaLayout);
        } else {
            createButton();
            linearLayout.addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 72, Gravity.BOTTOM));
        }

        if (button != null) {
            long totalSize = circleDiagramView.calculateSize();
            button.setSize(true, totalSize);
        }
    }

    private void syncCheckBoxes() {
        if (checkBoxes[CacheControlActivity.TYPE_PHOTOS] != null) {
            checkBoxes[CacheControlActivity.TYPE_PHOTOS].setChecked(clearViewData[CacheControlActivity.TYPE_PHOTOS].clear = cacheModel.allPhotosSelected, true);
        }
        if (checkBoxes[CacheControlActivity.TYPE_VIDEOS] != null) {
            checkBoxes[CacheControlActivity.TYPE_VIDEOS].setChecked(clearViewData[CacheControlActivity.TYPE_VIDEOS].clear = cacheModel.allVideosSelected, true);
        }
        if (checkBoxes[CacheControlActivity.TYPE_DOCUMENTS] != null) {
            checkBoxes[CacheControlActivity.TYPE_DOCUMENTS].setChecked(clearViewData[CacheControlActivity.TYPE_DOCUMENTS].clear = cacheModel.allDocumentsSelected, true);
        }
        if (checkBoxes[CacheControlActivity.TYPE_MUSIC] != null) {
            checkBoxes[CacheControlActivity.TYPE_MUSIC].setChecked(clearViewData[CacheControlActivity.TYPE_MUSIC].clear = cacheModel.allMusicSelected, true);
        }
        if (checkBoxes[CacheControlActivity.TYPE_VOICE] != null) {
            checkBoxes[CacheControlActivity.TYPE_VOICE].setChecked(clearViewData[CacheControlActivity.TYPE_VOICE].clear = cacheModel.allVoiceSelected, true);
        }
    }

    @Override
    public void onViewCreated(FrameLayout containerView) {
        super.onViewCreated(containerView);
        recyclerListView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                if (nestedSizeNotifierLayout != null) {
                    setShowShadow(!nestedSizeNotifierLayout.isPinnedToTop());
                }
            }
        });

        if (nestedSizeNotifierLayout != null) {
            createButton();
            containerView.addView(button, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 72, Gravity.BOTTOM));
        }
    }

    private void createButton() {
        button = new CacheControlActivity.ClearCacheButton(getContext());
        button.button.setOnClickListener(v -> {
            AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
            long sizeToFree = circleDiagramView != null ? circleDiagramView.calculateSize() : 0;
            builder.setTitle(LocaleController.getString(R.string.ClearCache) + (sizeToFree > 0 ? " (" + AndroidUtilities.formatFileSize(sizeToFree) + ")" : ""));
            builder.setMessage(LocaleController.getString(R.string.ClearCacheForChat) + (sizeToFree > 0 ? "\n\n" + LuckgramCacheStrings.willFree(AndroidUtilities.formatFileSize(sizeToFree)) : ""));
            builder.setNegativeButton(LocaleController.getString(R.string.Cancel), (di, which) -> {
                dismiss();
            });
            builder.setPositiveButton(LocaleController.getString(R.string.Clear), (di, which) -> {
                dismiss();
                cacheDelegate.cleanupDialogFiles(entities, clearViewData, cacheModel);
            });
            AlertDialog alertDialog = builder.create();
            alertDialog.show();
            alertDialog.redPositive();
        });
        if (circleDiagramView != null) {
            long totalSize = circleDiagramView.calculateSize();
            button.setSize(true, totalSize);
        }
    }

    public interface Delegate {
        void onAvatarClick();

        void cleanupDialogFiles(CacheControlActivity.DialogFileEntities entities, StorageDiagramView.ClearViewData[] clearViewData, CacheModel cacheModel);
    }
}
