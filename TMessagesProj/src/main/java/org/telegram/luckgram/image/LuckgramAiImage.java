package org.telegram.luckgram.image;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;
import org.telegram.luckgram.LuckgramAI;
import org.telegram.luckgram.LuckgramAIError;
import org.telegram.luckgram.LuckgramFeatures;
import org.telegram.luckgram.LuckgramSettings;
import org.telegram.luckgram.LuckgramStrings;
import org.telegram.luckgram.ai.LuckgramAiChatActivity;
import org.telegram.luckgram.ai.LuckgramAiConversation;
import org.telegram.luckgram.ai.LuckgramAiRegistry;
import org.telegram.luckgram.ai.LuckgramAiStore;
import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.BasePermissionsActivity;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadialProgressView;
import org.telegram.ui.DialogsActivity;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Locale;

/**
 * spec 23: AI images in the AI assistant, entirely through 18's extension points — the "画图" quick
 * button, an input interceptor for "画" and "/image", and the {@code image} message type. Images
 * are kept in the conversation's own folder (removed with it and on logout).
 */
public final class LuckgramAiImage {

    private LuckgramAiImage() {
    }

    static final String TYPE = "image";
    private static final String STATE_PENDING = "pending", STATE_DONE = "done", STATE_ERROR = "error";

    public static boolean isEnabled() {
        return LuckgramSettings.isEnabled(LuckgramFeatures.AI_IMAGE);
    }

    /** spec 23 平台入口提示: the one registration line, called from the AI assistant's registry. */
    public static void register() {
        LuckgramAiRegistry.registerQuickAction(new LuckgramAiRegistry.QuickAction("image", "画图", "Draw", R.drawable.msg_photos,
            host -> host.setInput(LuckgramStrings.get("画 ", "/image "))).visibleWhen(LuckgramAiImage::isEnabled));
        LuckgramAiRegistry.registerInputInterceptor(new String[]{"/image", "画"}, (host, prefix, rest) -> {
            if (!isEnabled() || rest.isEmpty()) {
                return false;
            }
            host.addMessage(LuckgramAiConversation.Message.create(LuckgramAiConversation.Message.ROLE_USER, LuckgramAiConversation.Message.TYPE_TEXT, prefix + " " + rest));
            generate(host, rest);
            return true;
        });
        LuckgramAiRegistry.registerMessageType(TYPE, new Renderer());
    }

    /** Adds an image message and draws {@code prompt} into it. */
    static void generate(LuckgramAiRegistry.ChatHost host, String prompt) {
        LuckgramAiConversation.Message m = LuckgramAiConversation.Message.create(LuckgramAiConversation.Message.ROLE_ASSISTANT, TYPE, prompt);
        setState(m, STATE_PENDING, null, null);
        host.addMessage(m);
        final int account = host.getCurrentAccount();
        final String conversationId = host.getConversation().id;
        LuckgramAI.getInstance().image(host.getContext(), prompt, new LuckgramAI.Callback<LuckgramAI.Image>() {
            @Override
            public void onResult(LuckgramAI.Image image) {
                Utilities.globalQueue.postRunnable(() -> {
                    String ext = image.mimeType != null && image.mimeType.contains("jpeg") ? "jpg" : image.mimeType != null && image.mimeType.contains("webp") ? "webp" : "png";
                    File file = new File(LuckgramAiStore.conversationDir(account, conversationId), m.id + "." + ext);
                    boolean ok = false;
                    try (FileOutputStream out = new FileOutputStream(file)) {
                        out.write(image.data);
                        ok = true;
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                    final boolean saved = ok;
                    AndroidUtilities.runOnUIThread(() -> {
                        if (saved) {
                            setState(m, STATE_DONE, file.getAbsolutePath(), null);
                        } else {
                            setState(m, STATE_ERROR, null, LuckgramAIError.UPSTREAM_UNAVAILABLE);
                        }
                        host.updateMessage(m);
                    });
                });
            }

            @Override
            public void onError(LuckgramAIError error) {
                setState(m, STATE_ERROR, null, error.code);
                host.updateMessage(m);
            }
        });
    }

    private static void setState(LuckgramAiConversation.Message m, String state, String path, String error) {
        try {
            JSONObject d = m.data != null ? m.data : new JSONObject();
            d.put("state", state);
            if (path != null) d.put("path", path); else d.remove("path");
            if (error != null) d.put("error", error); else d.remove("error");
            m.data = d;
        } catch (Exception ignore) {
        }
    }

    static String errorText(String code) {
        if (LuckgramAIError.CONTENT_REJECTED.equals(code)) {
            // spec 23 需求 5
            return LuckgramStrings.get("这个描述无法生成图片，换个说法试试", "This description can't be drawn. Try wording it differently.");
        }
        return LuckgramStrings.errorMessage(code);
    }

    /** A copy outside app-internal storage: Telegram refuses to send or save internal files. */
    private static File exportCopy(File source) {
        try {
            File dir = FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE);
            File out = new File(dir, "luckgram_ai_" + System.currentTimeMillis() + source.getName().substring(source.getName().lastIndexOf('.')));
            try (FileInputStream in = new FileInputStream(source); FileOutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
            return out;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    /** spec 23 需求 3: Telegram's own chat picker, sent as a normal photo. */
    static void sendTo(BaseFragment fragment, File file) {
        File copy = exportCopy(file);
        if (copy == null) return;
        Bundle args = new Bundle();
        args.putBoolean("onlySelect", true);
        args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_FORWARD);
        DialogsActivity picker = new DialogsActivity(args);
        picker.setDelegate((dialogs, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            AccountInstance accountInstance = AccountInstance.getInstance(fragment.getCurrentAccount());
            for (org.telegram.messenger.MessagesStorage.TopicKey key : dids) {
                SendMessagesHelper.prepareSendingPhoto(accountInstance, copy.getAbsolutePath(), null, key.dialogId, null, null, null,
                    TextUtils.isEmpty(message) ? null : message, null, null, null, 0, null, notify, scheduleDate, 0, null);
            }
            dialogs.finishFragment();
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.forward, LuckgramStrings.get("图片已发送", "Image sent")).show();
            return true;
        });
        fragment.presentFragment(picker);
    }

    static void save(BaseFragment fragment, File file) {
        Activity activity = fragment.getParentActivity();
        if (activity == null) return;
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT <= 28 && activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, BasePermissionsActivity.REQUEST_CODE_EXTERNAL_STORAGE);
            return;
        }
        File copy = exportCopy(file);
        if (copy == null) return;
        String mime = copy.getName().toLowerCase(Locale.ROOT).endsWith(".jpg") ? "image/jpeg" : "image/png";
        MediaController.saveFile(copy.getAbsolutePath(), activity, 0, null, mime, uri -> {
            BulletinFactory.of(fragment).createSimpleBulletin(R.raw.ic_download, LuckgramStrings.get("已保存到相册", "Saved to gallery")).show();
            //noinspection ResultOfMethodCallIgnored
            copy.delete();
        });
    }

    /** The image bubble: placeholder while drawing, then the picture with 发送给… / 保存 / 重画. */
    private static final class Renderer implements LuckgramAiRegistry.MessageRenderer {

        @Override
        public View createView(Context context, Theme.ResourcesProvider resourcesProvider) {
            return new ImageBubble(context);
        }

        @Override
        public void bind(View view, LuckgramAiConversation.Message message, boolean outgoing) {
            ((ImageBubble) view).bind(message);
        }

        @Override
        public String contextText(LuckgramAiConversation.Message message) {
            return null; // images are not sent to the chat model
        }

        @Override
        public String copyText(LuckgramAiConversation.Message message) {
            return message.text;
        }
    }

    private static final class ImageBubble extends LinearLayout {
        private final FrameLayout frame;
        private final ImageView image;
        private final RadialProgressView progress;
        private final TextView status;
        private final LinearLayout actions;
        private LuckgramAiConversation.Message message;
        private String boundPath;

        ImageBubble(Context context) {
            super(context);
            setOrientation(VERTICAL);
            frame = new FrameLayout(context);
            frame.setBackground(Theme.createRoundRectDrawable(dp(12), Theme.getColor(Theme.key_chat_inLoader) & 0x33FFFFFF));
            image = new ImageView(context);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setClipToOutline(true);
            image.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(12));
                }
            });
            frame.addView(image, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            progress = new RadialProgressView(context);
            progress.setSize(dp(32));
            progress.setProgressColor(Theme.getColor(Theme.key_chat_inLoader));
            frame.addView(progress, LayoutHelper.createFrame(48, 48, Gravity.CENTER));
            addView(frame, LayoutHelper.createLinear(240, 240));
            status = new TextView(context);
            status.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            status.setTextColor(Theme.getColor(Theme.key_chat_messageTextIn));
            status.setMaxWidth(dp(240));
            addView(status, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));
            actions = new LinearLayout(context);
            actions.setOrientation(HORIZONTAL);
            addView(actions, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));
            actions.addView(action(context, LuckgramStrings.get("发送给…", "Send to…"), v -> withFile((fragment, file) -> sendTo(fragment, file))));
            actions.addView(action(context, LuckgramStrings.get("保存", "Save"), v -> withFile((fragment, file) -> save(fragment, file))));
            actions.addView(action(context, LuckgramStrings.get("重画", "Redraw"), v -> redraw()));
        }

        private TextView action(Context context, String text, OnClickListener click) {
            TextView t = new TextView(context);
            t.setText(text);
            t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            t.setTypeface(AndroidUtilities.bold());
            t.setTextColor(Theme.getColor(Theme.key_chat_messageLinkIn));
            t.setPadding(dp(8), dp(6), dp(8), dp(6));
            t.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 2));
            t.setOnClickListener(click);
            return t;
        }

        private interface FileAction {
            void run(BaseFragment fragment, File file);
        }

        private LuckgramAiChatActivity page() {
            BaseFragment f = org.telegram.ui.LaunchActivity.getLastFragment();
            return f instanceof LuckgramAiChatActivity ? (LuckgramAiChatActivity) f : null;
        }

        private void withFile(FileAction action) {
            if (message == null || message.data == null) return;
            String path = message.data.optString("path", null);
            LuckgramAiChatActivity page = page();
            if (path == null || page == null) return;
            File f = new File(path);
            if (!f.exists()) return;
            action.run(page, f);
        }

        private void redraw() {
            LuckgramAiChatActivity page = page();
            if (message == null || page == null || TextUtils.isEmpty(message.text)) return;
            generate(page, message.text);
        }

        void bind(LuckgramAiConversation.Message m) {
            message = m;
            String state = m.data != null ? m.data.optString("state", STATE_PENDING) : STATE_PENDING;
            String path = m.data != null ? m.data.optString("path", null) : null;
            if (STATE_DONE.equals(state) && path != null) {
                progress.setVisibility(GONE);
                frame.setVisibility(VISIBLE);
                if (!path.equals(boundPath)) {
                    boundPath = path;
                    image.setImageBitmap(null);
                    final String p = path;
                    Utilities.globalQueue.postRunnable(() -> {
                        BitmapFactory.Options o = new BitmapFactory.Options();
                        o.inSampleSize = 2;
                        Bitmap b = BitmapFactory.decodeFile(p, o);
                        AndroidUtilities.runOnUIThread(() -> {
                            if (p.equals(boundPath)) image.setImageBitmap(b);
                        });
                    });
                }
                status.setText(m.text);
                status.setTextColor(Theme.getColor(Theme.key_chat_messageTextIn));
                actions.setVisibility(VISIBLE);
                actions.getChildAt(0).setVisibility(VISIBLE);
                actions.getChildAt(1).setVisibility(VISIBLE);
            } else if (STATE_ERROR.equals(state)) {
                frame.setVisibility(GONE);
                boundPath = null;
                status.setText(errorText(m.data.optString("error", LuckgramAIError.UPSTREAM_UNAVAILABLE)));
                status.setTextColor(Theme.getColor(Theme.key_text_RedRegular));
                actions.setVisibility(VISIBLE);
                actions.getChildAt(0).setVisibility(GONE);
                actions.getChildAt(1).setVisibility(GONE);
            } else {
                // spec 23 需求 2: placeholder while drawing
                frame.setVisibility(VISIBLE);
                progress.setVisibility(VISIBLE);
                image.setImageBitmap(null);
                boundPath = null;
                status.setText(LuckgramStrings.get("正在画…", "Drawing…"));
                status.setTextColor(Theme.getColor(Theme.key_chat_inTimeText));
                actions.setVisibility(GONE);
            }
        }
    }
}
